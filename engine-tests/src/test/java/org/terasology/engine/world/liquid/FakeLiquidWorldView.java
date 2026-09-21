// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.world.liquid;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.block.BlockRegion;

import java.util.Map;
import java.util.Set;

/**
 * A world in two hash maps, for testing the flow solver without an engine.
 * <p>
 * The headless test environment cannot stand in for this: its world provider answers {@code false} to every
 * relevance question and nought to every extra-data read, so the solver would refuse its own every read and
 * write and each test would pass by doing nothing whatsoever.
 */
public class FakeLiquidWorldView implements LiquidWorldView {

    private final Map<Vector3i, Block> blocks = Maps.newHashMap();
    private final Map<Vector3i, Integer> flows = Maps.newHashMap();
    private final BlockRegion relevant;
    private final Block air;

    /**
     * How many blocks have been written, so a test can assert on the work done rather than only on the
     * state reached - which is the only way to catch an oscillation that settles.
     */
    private int writes;

    /**
     * Run just after a batch of blocks lands, to look at the world the way the save thread would.
     */
    private Runnable onBlocksWritten;

    public FakeLiquidWorldView(BlockRegion relevant, Block air) {
        this.relevant = relevant;
        this.air = air;
    }

    @Override
    public boolean isRelevant(Vector3ic pos) {
        return relevant.contains(pos);
    }

    @Override
    public Block getBlock(Vector3ic pos) {
        return blocks.getOrDefault(new Vector3i(pos), air);
    }

    @Override
    public int getFlow(Vector3ic pos) {
        return flows.getOrDefault(new Vector3i(pos), 0);
    }

    @Override
    public void setFlow(Vector3ic pos, int value) {
        flows.put(new Vector3i(pos), value);
    }

    @Override
    public void setBlocks(Map<Vector3ic, Block> written) {
        for (Map.Entry<Vector3ic, Block> entry : written.entrySet()) {
            if (!isRelevant(entry.getKey())) {
                throw new AssertionError("The solver wrote outside the relevant region, at " + entry.getKey());
            }
            blocks.put(new Vector3i(entry.getKey()), entry.getValue());
            writes++;
        }
        if (onBlocksWritten != null) {
            onBlocksWritten.run();
        }
    }

    /**
     * Stands where the save thread stands: called once the blocks of a batch are down, before the caller has
     * had the chance to do anything else.
     * <p>
     * A real chunk is snapshotted for saving from another thread, and the snapshot takes the block array and
     * the data arrays one after the other. There is no way to make that race happen on demand, but there is
     * no need to: what it can see is exactly what is true at this instant, so looking here is looking with
     * the save thread's eyes.
     */
    public void onBlocksWritten(Runnable hook) {
        this.onBlocksWritten = hook;
    }

    /**
     * The positions holding a liquid whose distance reads nought, other than those a test declared sources.
     * <p>
     * Nought means source, and a source never dries up, so a running liquid caught at nought is a liquid
     * made immortal. This is the shape the defect took: not a wrong value in memory, but a wrong value
     * visible for an instant - and an instant is all a snapshot needs.
     */
    public Set<Vector3ic> livingLiquidsCallingThemselvesSources(Set<Vector3ic> declaredSources) {
        Set<Vector3ic> caught = Sets.newLinkedHashSet();
        for (Map.Entry<Vector3i, Block> entry : blocks.entrySet()) {
            if (entry.getValue().isLiquid() && entry.getValue().getFlowRange() > 0
                    && getFlow(entry.getKey()) == 0 && !declaredSources.contains(entry.getKey())) {
                caught.add(new Vector3i(entry.getKey()));
            }
        }
        return caught;
    }

    @Override
    public Block getAir() {
        return air;
    }

    public void put(int x, int y, int z, Block block) {
        blocks.put(new Vector3i(x, y, z), block);
    }

    public void put(int x, int y, int z, Block block, int flow) {
        put(x, y, z, block);
        flows.put(new Vector3i(x, y, z), flow);
    }

    public Block at(int x, int y, int z) {
        return getBlock(new Vector3i(x, y, z));
    }

    public int flowAt(int x, int y, int z) {
        return getFlow(new Vector3i(x, y, z));
    }

    public int getWrites() {
        return writes;
    }

    public long count(Block block) {
        return blocks.values().stream().filter(b -> b.equals(block)).count();
    }
}
