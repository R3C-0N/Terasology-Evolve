// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.world.propagation.light;

import org.joml.Vector3ic;
import org.terasology.engine.math.Side;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.chunks.Chunk;
import org.terasology.engine.world.chunks.Chunks;

/**
 * Rules for how the warm share of the block light propagates.
 * <p>
 * Warmth is what tells the glow of molten rock from the near white of a flame. It travels as its own channel because
 * nothing else can carry it: a lit surface only knows how much light reaches it, never which block sent it, and the
 * mesh bakes that light down to one number per vertex long before anything could ask.
 * <p>
 * Every rule here is the light's rule unchanged - the same fall off, the same ceiling, and the inherited
 * {@link CommonLightPropagationRules} for what a block lets through. That is deliberate, and load bearing: because
 * warmth follows exactly the paths light follows, and starts at or below the luminance, it can never exceed the light
 * at any point. The mesher divides one by the other, so an invariant broken here comes out as a share above one.
 */
public class WarmthPropagationRules extends CommonLightPropagationRules {

    @Override
    public byte getFixedValue(Block block, Vector3ic pos) {
        return block.getWarmth();
    }

    @Override
    public byte propagateValue(byte existingValue, Side side, Block from, int scale) {
        return (byte) Math.max(existingValue - scale, 0);
    }

    @Override
    public byte getMaxValue() {
        return Chunks.MAX_LIGHT; // 15, the same scale as the light this is a share of
    }

    @Override
    public byte getValue(Chunk chunk, Vector3ic pos) {
        return getValue(chunk, pos.x(), pos.y(), pos.z());
    }

    @Override
    public byte getValue(Chunk chunk, int x, int y, int z) {
        return chunk.getWarmth(x, y, z);
    }

    @Override
    public void setValue(Chunk chunk, Vector3ic pos, byte value) {
        chunk.setWarmth(pos, value);
    }
}
