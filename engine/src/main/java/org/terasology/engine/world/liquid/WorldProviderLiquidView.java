// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.world.liquid;

import com.google.common.collect.Maps;
import org.joml.Vector3ic;
import org.terasology.engine.world.WorldProvider;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.block.BlockManager;

import java.util.Map;

/**
 * The flow solver's window on the real world.
 */
public class WorldProviderLiquidView implements LiquidWorldView {

    private final WorldProvider worldProvider;
    private final int flowSlot;
    private final BlockManager blockManager;
    private final Block air;

    /**
     * Blocks already looked up by name, the misses kept as well.
     * <p>
     * A name is asked for once per cell that sets, and a lava front against the sea sets a great many cells.
     * Holding the misses is what keeps a mistyped name from parsing a URI on every one of them.
     */
    private final Map<String, Block> resolved = Maps.newHashMap();

    public WorldProviderLiquidView(WorldProvider worldProvider, int flowSlot, BlockManager blockManager) {
        this.worldProvider = worldProvider;
        this.flowSlot = flowSlot;
        this.blockManager = blockManager;
        this.air = blockManager.getBlock(BlockManager.AIR_ID);
    }

    @Override
    public boolean isRelevant(Vector3ic pos) {
        return worldProvider.isBlockRelevant(pos);
    }

    @Override
    public Block getBlock(Vector3ic pos) {
        return worldProvider.getBlock(pos);
    }

    @Override
    public int getFlow(Vector3ic pos) {
        return worldProvider.getExtraData(flowSlot, pos.x(), pos.y(), pos.z());
    }

    @Override
    public void setFlow(Vector3ic pos, int value) {
        worldProvider.setExtraData(flowSlot, pos, value);
    }

    @Override
    public void setBlocks(Map<Vector3ic, Block> blocks) {
        worldProvider.setBlocks(blocks);
    }

    @Override
    public Block getAir() {
        return air;
    }

    /**
     * {@inheritDoc}
     * <p>
     * A block manager hands back air for a name it does not know, and air is exactly the answer that must not
     * be believed here: taken at face value it would turn the lava it was asked about into nothing at all,
     * which looks like a rule that works and is a rule that deletes. A name that comes back as air is therefore
     * reported as no block, and the solver says so once.
     */
    @Override
    public Block resolve(String blockUri) {
        if (resolved.containsKey(blockUri)) {
            return resolved.get(blockUri);
        }
        Block block = blockManager.getBlock(blockUri);
        if (block != null && block.equals(air)) {
            block = null;
        }
        // A miss is kept as readily as a hit: a family that merely had not been read yet is loaded by the ask
        // itself, so a name that misses now is a name that does not exist and will not start to.
        resolved.put(blockUri, block);
        return block;
    }
}
