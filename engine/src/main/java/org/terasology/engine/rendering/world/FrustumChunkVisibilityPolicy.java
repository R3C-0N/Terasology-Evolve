// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.world;

import org.terasology.joml.geom.AABBfc;
import org.terasology.engine.rendering.cameras.Camera;
import org.terasology.engine.world.chunks.RenderableChunk;

/**
 * The frustum test the renderer has always done, kept word for word so that a world without its own
 * policy behaves exactly as before.
 */
public final class FrustumChunkVisibilityPolicy implements ChunkVisibilityPolicy {

    public static final FrustumChunkVisibilityPolicy INSTANCE = new FrustumChunkVisibilityPolicy();

    private FrustumChunkVisibilityPolicy() {
    }

    @Override
    public boolean isVisible(Camera camera, RenderableChunk chunk) {
        return camera.hasInSight(chunk.getAABB());
    }

    @Override
    public boolean isVisibleReflected(Camera camera, RenderableChunk chunk) {
        AABBfc bounds = chunk.getAABB();
        return camera.getViewFrustumReflected().testAab(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }
}
