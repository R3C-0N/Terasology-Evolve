// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.primitives;

import org.terasology.engine.registry.CoreRegistry;
import org.terasology.engine.world.ChunkView;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.chunks.blockdata.ExtraBlockDataManager;
import org.terasology.engine.world.liquid.LiquidExtraDataSystem;

/**
 * How high the surface of a liquid stands at each corner of a block, so that a flow can be seen to run downhill.
 *
 * A block is a cube and a flowing liquid is not. What the flow solver already knows - how many sideways steps this
 * block is from its source - is exactly the number that says how thin the sheet should be here, and it is the one the
 * mesh lacks: a shape knows its own outline, never what its neighbours are doing.
 *
 * Reading the distance of one column would give a staircase, a step at every block edge. So the height is taken at the
 * four <em>corners</em> instead, each averaged over the four columns that touch it. Two neighbouring blocks share two
 * corners, and a corner is a function of the columns around it alone, so both blocks arrive at the same value and the
 * surface is continuous by construction rather than by care.
 *
 * A source carries nought and comes out at {@link #HIGH}, the height liquids have always had - so a generated sea,
 * every block of which is a source, is not moved by a hair. Only what has actually run anywhere is lowered.
 *
 * The slot number is resolved from the registry rather than injected: the mesher is built without a context
 * ({@code ChunkTessellator} has a no-argument constructor) and {@link org.terasology.engine.world.block.shapes.BlockMeshPart}
 * holds no dependency at all. Where there is no registry - a headless path, a test - no slot resolves and nothing is
 * deformed, which is the right answer rather than a failure.
 */
public final class LiquidSurfaceField {

    /** The top of {@code engine:trimmedLoweredCube}, and so the height of a source: unchanged from before. */
    public static final float HIGH = 0.4f;

    /** The top of a block, for a corner that has liquid standing above it. */
    public static final float FULL = 0.5f;

    /** The far end of a run, leaving a film of {@code LOW + 0.5} of a block. */
    public static final float LOW = -0.35f;

    /** Width of the sampled neighbourhood, one column either side. */
    public static final int SIDE = 3;

    /** A column given {@link #ABSENT} does not carry the liquid in question and takes no part in any average. */
    public static final int ABSENT = -1;

    private static final int MISSING = -1;

    private static volatile Slot resolved;

    private LiquidSurfaceField() {
    }

    /**
     * Whether this liquid's surface takes its shape from the flow at all. A liquid that cannot run has no distance
     * worth reading, so it keeps the shape it has always had.
     */
    public static boolean deforms(Block block) {
        return block.isLiquid() && block.getFlowRange() > 0;
    }

    /**
     * The four corner heights of one block, in the order given by {@link #cornerIndex}, or null where the flow field
     * is unavailable and the block should keep its undeformed shape.
     */
    public static float[] corners(ChunkView view, Block block, int x, int y, int z) {
        int slot = slot();
        if (slot < 0) {
            return null;
        }

        int[] field = new int[SIDE * SIDE];
        boolean[] overhead = new boolean[SIDE * SIDE];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int at = (dx + 1) + SIDE * (dz + 1);
                // Same instance, not merely "also a liquid": water and lava must not average together, they must
                // meet at a clean step.
                if (view.getBlock(x + dx, y, z + dz) != block) {
                    field[at] = ABSENT;
                    continue;
                }
                field[at] = view.getExtraData(slot, x + dx, y, z + dz);
                overhead[at] = view.getBlock(x + dx, y + 1, z + dz) == block;
            }
        }

        int[] flows = new int[4];
        boolean[] above = new boolean[4];
        float[] out = new float[4];
        for (int sz = -1; sz <= 1; sz += 2) {
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int i = 0; i < 4; i++) {
                    int dx = (i & 1) == 0 ? 0 : sx;
                    int dz = (i & 2) == 0 ? 0 : sz;
                    int at = (dx + 1) + SIDE * (dz + 1);
                    flows[i] = field[at];
                    above[i] = overhead[at];
                }
                out[cornerIndex(sx, sz)] = cornerHeight(flows, above, block.getFlowRange());
            }
        }
        return out;
    }

    /**
     * Where the corner towards {@code (sx, sz)} is kept in the array {@link #corners} returns, and the same order a
     * vertex is looked up by from the sign of its own coordinates.
     */
    public static int cornerIndex(float sx, float sz) {
        return (sx > 0 ? 1 : 0) + 2 * (sz > 0 ? 1 : 0);
    }

    /**
     * The height of one corner, from the four columns that touch it.
     *
     * Kept free of the world so it can be tested on two plain arrays - everything that can go wrong here is
     * arithmetic, and none of it needs a chunk to go wrong in - and so that a CPU mirror of the drawn surface, such
     * as the one deciding whether the camera is underwater, can call the same code rather than reproduce it.
     *
     * The distances are summed as integers and divided once, rather than averaged as heights. That is not tidiness:
     * the four blocks that share a corner each present these same four columns in a different order, and a float sum
     * is not associative, so averaging heights would let them disagree in the last bit and open a hairline crack
     * along every block edge. An integer sum is exact and order independent, and the mapping is affine, so the mean
     * of the heights is the height of the mean.
     *
     * @param flows the distance each of the four columns has run, or {@link #ABSENT} where the column does not carry
     *         this liquid at all. Order is immaterial, which is exactly what keeps the surface watertight.
     * @param above whether each of those columns carries the liquid one block higher
     * @param flowRange the reach of the liquid, from {@link Block#getFlowRange}
     */
    public static float cornerHeight(int[] flows, boolean[] above, int flowRange) {
        int span = flowRange + 1;
        int sum = 0;
        int counted = 0;
        for (int i = 0; i < flows.length; i++) {
            // Liquid standing over any of the four pulls the corner up to the top of the block, so that a fall meets
            // the pool it lands in instead of leaving a crevasse beside it.
            if (above[i]) {
                return FULL;
            }
            if (flows[i] == ABSENT) {
                continue;
            }
            sum += Math.min(Math.max(flows[i], 0), span);
            counted++;
        }
        if (counted == 0) {
            return HIGH;
        }
        return HIGH - (sum / (float) counted) * (HIGH - LOW) / span;
    }

    private static int slot() {
        ExtraBlockDataManager manager = CoreRegistry.get(ExtraBlockDataManager.class);
        Slot known = resolved;
        if (known != null && known.manager == manager) {
            return known.index;
        }
        int index = MISSING;
        if (manager != null) {
            try {
                index = manager.getSlotNumber(LiquidExtraDataSystem.FLOW_FIELD);
            } catch (IllegalArgumentException e) {
                // No liquid in this environment claimed the field; nothing flows, so nothing slopes.
                index = MISSING;
            }
        }
        // Written as one object so a mesher thread can never read a manager paired with another's slot number.
        resolved = new Slot(manager, index);
        return index;
    }

    private static final class Slot {
        private final ExtraBlockDataManager manager;
        private final int index;

        Slot(ExtraBlockDataManager manager, int index) {
            this.manager = manager;
            this.index = index;
        }
    }
}
