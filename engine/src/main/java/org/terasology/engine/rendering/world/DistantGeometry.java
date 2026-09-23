// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.world;

import org.terasology.engine.rendering.assets.material.Material;
import org.terasology.engine.rendering.cameras.Camera;
import org.terasology.engine.rendering.primitives.ChunkMesh;

/**
 * What a world draws past its loaded chunks, handed to the passes that draw the chunks.
 * <p>
 * The distance should look like the terrain, and the surest way is to go through the very passes
 * that draw the terrain: the opaque blocks, the water, the shadow map. Each has set up its buffer,
 * its textures, its uniforms and its camera by the time it has drawn its chunks, and asks this for
 * the same phase with the same material; nothing about the pass has to be copied anywhere, so
 * nothing can drift. A world that draws a distant terrain publishes this in the context.
 */
public interface DistantGeometry {

    /**
     * Draws the distant geometry of a phase, with the material and state the calling pass has set up,
     * seen from its camera. The material is left as it was found.
     *
     * @param shadow whether the camera is the light's: it is not an eye, and has no horizon
     * @return the triangles drawn
     */
    int render(ChunkMesh.RenderPhase phase, Material material, Camera camera, boolean shadow);
}
