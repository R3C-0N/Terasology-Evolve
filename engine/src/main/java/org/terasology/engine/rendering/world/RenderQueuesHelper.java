// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.world;

import org.terasology.engine.world.chunks.RenderableChunk;

import java.util.PriorityQueue;

public class RenderQueuesHelper {
    public final PriorityQueue<RenderableChunk> chunksOpaque;
    public final PriorityQueue<RenderableChunk> chunksOpaqueShadow;
    public final PriorityQueue<RenderableChunk> chunksOpaqueReflection;
    public final PriorityQueue<RenderableChunk> chunksAlphaReject;
    public final PriorityQueue<RenderableChunk> chunksAlphaBlend;

    public RenderQueuesHelper(PriorityQueue<RenderableChunk> chunksOpaque,
                       PriorityQueue<RenderableChunk> chunksOpaqueShadow,
                       PriorityQueue<RenderableChunk> chunksOpaqueReflection,
                       PriorityQueue<RenderableChunk> chunksAlphaReject,
                       PriorityQueue<RenderableChunk> chunksAlphaBlend) {

        this.chunksOpaque = chunksOpaque;
        this.chunksOpaqueShadow = chunksOpaqueShadow;
        this.chunksOpaqueReflection = chunksOpaqueReflection;
        this.chunksAlphaReject = chunksAlphaReject;
        this.chunksAlphaBlend = chunksAlphaBlend;
    }

    /**
     * Whether any visible chunk holds a refractive surface, water above all.
     * <p>
     * The queues are filled before the render graph runs, so a pass can ask this to find out whether what it draws will
     * be looked at: with no water on screen, nothing samples the reflected scene.
     */
    public boolean hasRefractiveChunks() {
        return !chunksAlphaBlend.isEmpty();
    }

    /**
     * Remove any remaining data from all queues, to avoid a memory leak in the case that the nodes using that data aren't present.
     */
    public void clear() {
        chunksOpaque.clear();
        chunksOpaqueShadow.clear();
        chunksOpaqueReflection.clear();
        chunksAlphaReject.clear();
        chunksAlphaBlend.clear();
    }
}
