// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.world;

import org.terasology.context.annotation.API;
import org.terasology.engine.rendering.cameras.Camera;
import org.terasology.engine.world.chunks.RenderableChunk;

/**
 * Decides whether a chunk is worth drawing.
 * <p>
 * The default answer is a frustum test against the chunk's bounding box, and that is right for as
 * long as a chunk is drawn where it is stored. A world whose vertices are bent by the shader breaks
 * that assumption: the box no longer bounds the geometry, and chunks vanish or linger. Such a world
 * puts its own policy into the context.
 */
@API
public interface ChunkVisibilityPolicy {

    boolean isVisible(Camera camera, RenderableChunk chunk);

    boolean isVisibleReflected(Camera camera, RenderableChunk chunk);
}
