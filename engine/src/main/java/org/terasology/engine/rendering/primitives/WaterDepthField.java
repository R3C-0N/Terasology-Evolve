// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.primitives;

import org.terasology.engine.world.ChunkView;
import org.terasology.engine.world.chunks.Chunks;

import java.util.HashMap;
import java.util.Map;

/**
 * How much water there is under each point of a water surface, as a level that never jumps.
 *
 * The swell is a function of the world position alone, so it is the same everywhere: the same eight tenths of a block
 * of travel in the open sea and in a hand of water over the sand. Real water cannot do that — a wave carries the
 * column beneath it, and where there is no column there is no wave. Depth is the number that says so, and it is the
 * one the shader lacks: a vertex knows where it is, never what is under it.
 *
 * Raw depth is not usable as it stands. A drop-off puts one block of water beside twenty, and a swell driven straight
 * off that reading would step from flat to rolling across a single block edge — a seam, not a coast. So the reading is
 * turned into a level, and the levels are made to climb one at a time: a column is never allowed to exceed any of its
 * eight neighbours by more than one, the lower always winning. The level is therefore the smallest of the raw depth
 * and of every shallower column's depth plus the number of blocks away it is. Around a beach, whose waterline is level
 * nought, that reads as one level gained per block out to sea — the swell comes up over {@link #RANGE} blocks however
 * brutal the bathymetry underneath.
 *
 * That lower envelope is exactly a distance transform with a unit kernel, so it costs two sweeps. It needs a margin
 * as wide as the range, since a shoal that far outside the chunk still has the right to hold its water down, and the
 * margin fits: the local view reaches a full chunk past the one being meshed. Depth itself is read straight down,
 * block by block, and the scan is cut short by how far out the column is — a column {@code k} blocks away can only
 * ever contribute its depth plus {@code k}, so past {@code RANGE - k} what is under it no longer decides anything.
 *
 * The cache is thread local because meshing is: one worker holds one view at a time, and a new view drops the levels
 * of the previous one.
 */
public final class WaterDepthField {

    /** Number of levels, and so the depth at which the swell is at full strength and the width of the ramp to it. */
    public static final float RANGE = 28.0f;

    private static final int PAD = (int) RANGE;
    private static final int SIDE = Chunks.SIZE_X + 2 * PAD;

    /** Beyond this many distinct water levels in one chunk the field is not worth its cost; the water just swells. */
    private static final int MAX_LEVELS = 32;

    private static final ThreadLocal<WaterDepthField> CURRENT = ThreadLocal.withInitial(WaterDepthField::new);

    private final Map<Integer, Level> levels = new HashMap<>();
    private ChunkView owner;

    private WaterDepthField() {
    }

    /**
     * The level under a point of the water surface, in chunk local coordinates, interpolated between column centres
     * so that it fades between levels rather than stepping between them.
     */
    public static float sample(ChunkView view, int y, float x, float z) {
        Level level = CURRENT.get().level(view, y);
        if (level == null) {
            return RANGE;
        }
        return level.sample(x + PAD, z + PAD);
    }

    private Level level(ChunkView view, int y) {
        if (owner != view) {
            owner = view;
            levels.clear();
        }
        Level level = levels.get(y);
        if (level == null && levels.size() < MAX_LEVELS) {
            level = new Level(view, y);
            levels.put(y, level);
        }
        return level;
    }

    private static final class Level {
        private final float[] level = new float[SIDE * SIDE];

        Level(ChunkView view, int y) {
            int cap = (int) RANGE;
            for (int j = 0; j < SIDE; j++) {
                int outZ = outside(j);
                for (int i = 0; i < SIDE; i++) {
                    int limit = cap - Math.max(outside(i), outZ);
                    int count = 0;
                    while (count < limit && view.getBlock(i - PAD, y - count, j - PAD).isLiquid()) {
                        count++;
                    }
                    level[j * SIDE + i] = count;
                }
            }
            climb();
        }

        /** How many blocks a padded column lies outside the chunk being meshed, in the eight-neighbour metric. */
        private static int outside(int index) {
            int at = index - PAD;
            if (at < 0) {
                return -at;
            }
            return at > Chunks.SIZE_X - 1 ? at - (Chunks.SIZE_X - 1) : 0;
        }

        /**
         * Lets no column stand more than one level above any of its eight neighbours, the lower one winning. Two
         * sweeps, forward and back, are enough for a unit kernel.
         */
        private void climb() {
            for (int j = 0; j < SIDE; j++) {
                for (int i = 0; i < SIDE; i++) {
                    int at = j * SIDE + i;
                    float best = level[at];
                    if (i > 0) {
                        best = Math.min(best, level[at - 1] + 1.0f);
                    }
                    if (j > 0) {
                        best = Math.min(best, level[at - SIDE] + 1.0f);
                        if (i > 0) {
                            best = Math.min(best, level[at - SIDE - 1] + 1.0f);
                        }
                        if (i < SIDE - 1) {
                            best = Math.min(best, level[at - SIDE + 1] + 1.0f);
                        }
                    }
                    level[at] = best;
                }
            }
            for (int j = SIDE - 1; j >= 0; j--) {
                for (int i = SIDE - 1; i >= 0; i--) {
                    int at = j * SIDE + i;
                    float best = level[at];
                    if (i < SIDE - 1) {
                        best = Math.min(best, level[at + 1] + 1.0f);
                    }
                    if (j < SIDE - 1) {
                        best = Math.min(best, level[at + SIDE] + 1.0f);
                        if (i < SIDE - 1) {
                            best = Math.min(best, level[at + SIDE + 1] + 1.0f);
                        }
                        if (i > 0) {
                            best = Math.min(best, level[at + SIDE - 1] + 1.0f);
                        }
                    }
                    level[at] = best;
                }
            }
        }

        float sample(float x, float z) {
            float cx = Math.min(Math.max(x, 0.0f), SIDE - 1.0001f);
            float cz = Math.min(Math.max(z, 0.0f), SIDE - 1.0001f);
            int i = (int) cx;
            int j = (int) cz;
            float fx = cx - i;
            float fz = cz - j;
            int at = j * SIDE + i;
            float top = level[at] + (level[at + 1] - level[at]) * fx;
            float bottom = level[at + SIDE] + (level[at + SIDE + 1] - level[at + SIDE]) * fx;
            return top + (bottom - top) * fz;
        }
    }
}
