// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.world.liquid;

import com.google.common.collect.Maps;
import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.block.BlockRegion;

import java.util.Map;

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
