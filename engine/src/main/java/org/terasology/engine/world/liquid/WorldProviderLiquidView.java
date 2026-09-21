// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.world.liquid;

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
    private final Block air;

    public WorldProviderLiquidView(WorldProvider worldProvider, int flowSlot, BlockManager blockManager) {
        this.worldProvider = worldProvider;
        this.flowSlot = flowSlot;
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
}
