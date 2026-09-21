// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.world.liquid;

import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.terasology.engine.math.Side;
import org.terasology.engine.world.chunks.Chunk;
import org.terasology.engine.world.chunks.ChunkProvider;
import org.terasology.engine.world.chunks.Chunks;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Walks each chunk that arrives, looking for liquid that has somewhere to go.
 * <p>
 * This is the price of a living sea, and it is paid in full: a chunk saved mid-flood comes back out of
 * balance on the inside, so reconciling only the six faces would leave ghost puddles that nothing would ever
 * correct. The sweep is complete, but it is spread over many passes a slice at a time, so it costs
 * microseconds a frame rather than a stutter.
 */
public class LiquidSeeder {

    /**
     * Positions read per pass, half a chunk. A chunk with no liquid costs some sixty to a hundred
     * microseconds; a half-full ocean chunk some three to five tenths of a millisecond.
     */
    public static final int CELLS_PER_PASS = 32768;

    private static final int SLICE = Chunks.SIZE_X * Chunks.SIZE_Z;

    private final ChunkProvider chunkProvider;
    private final LiquidFlowSolver solver;
    private final LiquidWorldView view;
    private final Deque<Cursor> pending = new ArrayDeque<>();

    public LiquidSeeder(ChunkProvider chunkProvider, LiquidFlowSolver solver, LiquidWorldView view) {
        this.chunkProvider = chunkProvider;
        this.solver = solver;
        this.view = view;
    }

    /**
     * A freshly generated chunk goes to the front: a cave the generator dug under the sea should fill before
     * a chunk merely read back from disk is re-examined.
     */
    public void seedFirst(Vector3ic chunkPos) {
        pending.addFirst(new Cursor(chunkPos));
    }

    public void seedLast(Vector3ic chunkPos) {
        pending.addLast(new Cursor(chunkPos));
    }

    public void forget(Vector3ic chunkPos) {
        pending.removeIf(cursor -> cursor.chunkPos.equals(chunkPos));
    }

    public boolean isIdle() {
        return pending.isEmpty();
    }

    public void update() {
        int budget = CELLS_PER_PASS;
        while (budget > 0 && !pending.isEmpty()) {
            Cursor cursor = pending.peekFirst();
            Chunk chunk = chunkProvider.getChunk(cursor.chunkPos);
            if (chunk == null) {
                pending.pollFirst();
                continue;
            }
            Vector3i world = new Vector3i();
            while (budget > 0 && cursor.y < Chunks.SIZE_Y) {
                for (int x = 0; x < Chunks.SIZE_X; x++) {
                    for (int z = 0; z < Chunks.SIZE_Z; z++) {
                        if (!LiquidFlowSolver.isFlowLiquid(chunk.getBlock(x, cursor.y, z))) {
                            continue;
                        }
                        chunk.chunkToWorldPosition(x, cursor.y, z, world);
                        if (hasSomewhereToGo(world)) {
                            solver.enqueueFlow(world, view.getFlow(world));
                        }
                    }
                }
                cursor.y++;
                budget -= SLICE;
            }
            if (cursor.y >= Chunks.SIZE_Y) {
                pending.pollFirst();
            }
        }
    }

    private boolean hasSomewhereToGo(Vector3ic pos) {
        Vector3i down = new Vector3i(pos.x(), pos.y() - 1, pos.z());
        if (view.isRelevant(down) && LiquidFlowSolver.isFlowPassable(view.getBlock(down))) {
            return true;
        }
        for (Side side : Side.horizontalSides()) {
            Vector3i neighbour = side.getAdjacentPos(pos, new Vector3i());
            if (view.isRelevant(neighbour) && LiquidFlowSolver.isFlowPassable(view.getBlock(neighbour))) {
                return true;
            }
        }
        return false;
    }

    private static final class Cursor {
        private final Vector3i chunkPos;
        private int y;

        private Cursor(Vector3ic chunkPos) {
            this.chunkPos = new Vector3i(chunkPos);
        }
    }
}
