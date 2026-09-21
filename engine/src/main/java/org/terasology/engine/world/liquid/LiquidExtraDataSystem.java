// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.world.liquid;

import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.chunks.blockdata.ExtraDataSystem;
import org.terasology.engine.world.chunks.blockdata.RegisterExtraData;

/**
 * Claims the four bits per block in which the flow solver keeps each liquid's distance from its source.
 * <p>
 * A full block is only an identifier in the chunk's array and can hold no state of its own, so the distance
 * lives here instead. Nought means a source, which is why world generation needs no pass of its own: a
 * sparse data array reads nought everywhere until something writes to it, so every block of the generated
 * sea is already a permanent source, at a cost of nine bytes per chunk.
 */
@ExtraDataSystem
public final class LiquidExtraDataSystem {

    /**
     * The name of the data field, to be handed to {@code ExtraBlockDataManager.getSlotNumber}.
     */
    public static final String FLOW_FIELD = "engine.liquidFlow";

    private LiquidExtraDataSystem() {
    }

    @RegisterExtraData(name = FLOW_FIELD, bitSize = 4)
    public static boolean hasLiquidFlow(Block block) {
        return block.isLiquid();
    }
}
