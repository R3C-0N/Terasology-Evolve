// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.world.internal;

import org.joml.RoundingMode;
import org.joml.Vector3fc;
import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.terasology.engine.world.WorldChangeListener;
import org.terasology.engine.world.WorldProvider;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.block.BlockRegionc;
import org.terasology.engine.world.chunks.blockdata.ExtraBlockDataManager;

import javax.inject.Inject;
import java.util.Collection;

public class WorldProviderWrapper extends AbstractWorldProviderDecorator implements WorldProvider {
    private ExtraBlockDataManager extraDataManager;

    @Inject
    public WorldProviderWrapper(WorldProviderCore core, ExtraBlockDataManager extraDataManager) {
        super(core);
        this.extraDataManager = extraDataManager;
    }

    @Override
    public boolean isBlockRelevant(Vector3ic pos) {
        return base.isBlockRelevant(pos.x(), pos.y(), pos.z());
    }

    @Override
    public boolean isBlockRelevant(Vector3fc pos) {
        return isBlockRelevant(new Vector3i(pos, RoundingMode.HALF_UP));
    }

    @Override
    public Block setBlock(Vector3ic pos, Block type) {
        return base.setBlock(pos, type);
    }

    @Override
    public Block getBlock(Vector3fc pos) {
        return getBlock(new Vector3i(pos, RoundingMode.HALF_UP));
    }

    @Override
    public byte getLight(Vector3ic pos) {
        return base.getLight(pos.x(), pos.y(), pos.z());
    }

    /**
     * HALF_UP, like its three neighbours, and not FLOOR.
     * <p>
     * A block at integer y owns the span [y - 0.5, y + 0.5], so truncating a float position answers with the
     * block <em>below</em> for the whole lower half of every block. {@code getBlock}, {@code getSunlight} and
     * {@code getTotalLight} all round; this one did not, and the difference showed up as entities rendered
     * pitch black underground: a creature whose centre sits under its block's midline was lit by the rock
     * beneath its feet, where the block light is zero. Tall creatures happened to clear the midline and were
     * lit correctly, which is why it read as "some meshes are broken" rather than as a rounding mistake.
     */
    @Override
    public byte getLight(Vector3fc pos) {
        return getLight(new Vector3i(pos, RoundingMode.HALF_UP));
    }

    @Override
    public byte getSunlight(Vector3fc pos) {
        return getSunlight(new Vector3i(pos, RoundingMode.HALF_UP));
    }

    @Override
    public byte getTotalLight(Vector3fc pos) {
        return getTotalLight(new Vector3i(pos, RoundingMode.HALF_UP));
    }

    @Override
    public byte getSunlight(Vector3ic pos) {
        return base.getSunlight(pos.x(), pos.y(), pos.z());
    }

    @Override
    public byte getTotalLight(Vector3ic pos) {
        return base.getTotalLight(pos.x(), pos.y(), pos.z());
    }

    public int setExtraData(int index, int x, int y, int z, int value) {
        return base.setExtraData(index, new Vector3i(x, y, z), value);
    }

    public int getExtraData(String fieldName, int x, int y, int z) {
        return base.getExtraData(extraDataManager.getSlotNumber(fieldName), x, y, z);
    }

    public int setExtraData(String fieldName, int x, int y, int z, int value) {
        return base.setExtraData(extraDataManager.getSlotNumber(fieldName), new Vector3i(x, y, z), value);
    }

    public int setExtraData(String fieldName, Vector3ic pos, int value) {
        return base.setExtraData(extraDataManager.getSlotNumber(fieldName), pos, value);
    }

    @Override
    public void processPropagation() {
        base.processPropagation();
    }

    @Override
    public void registerListener(WorldChangeListener listener) {
        base.registerListener(listener);
    }

    @Override
    public void unregisterListener(WorldChangeListener listener) {
        base.unregisterListener(listener);
    }

    @Override
    public Collection<BlockRegionc> getRelevantRegions() {
        return base.getRelevantRegions();
    }
}
