// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.world.liquid;

import org.joml.Vector3ic;
import org.terasology.engine.world.block.Block;

import java.util.Map;

/**
 * The narrow window on the world through which the flow solver works, in the shape of
 * {@code PropagatorWorldView}.
 * <p>
 * It exists so the solver can be tested without a world. The headless test environment's world provider
 * answers {@code false} to every relevance question and nought to every extra-data read, so a solver written
 * straight against it would reject its own every read and write, and each of its tests would pass by doing
 * nothing at all.
 */
public interface LiquidWorldView {

    /**
     * Whether this position sits in a chunk that is loaded and fully generated.
     * <p>
     * It must be asked before every read as well as every write: an unloaded position reads back as
     * {@code engine:unloaded} rather than air, and a write to one is dropped on the floor.
     */
    boolean isRelevant(Vector3ic pos);

    Block getBlock(Vector3ic pos);

    /**
     * @return the distance from the source stored at this position, nought meaning a source
     */
    int getFlow(Vector3ic pos);

    void setFlow(Vector3ic pos, int value);

    /**
     * Writes a batch of blocks in one pass, so the change notifications are grouped.
     */
    void setBlocks(Map<Vector3ic, Block> blocks);

    /**
     * @return the block that stands for nothing at all, used to dry a position up
     */
    Block getAir();

    /**
     * Looks up a block by the name a block definition wrote, for the block a liquid sets into.
     * <p>
     * It sits here for the same reason {@link #getAir()} does: the solver knows {@link Block} and nothing else
     * of the engine, so it cannot reach a block manager, and a test drives it from a plain map.
     *
     * @param blockUri the name as written, {@code Module:Block}
     * @return the block, or {@code null} if no such block is registered
     */
    Block resolve(String blockUri);
}
