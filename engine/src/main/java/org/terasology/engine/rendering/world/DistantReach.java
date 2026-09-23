// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.world;

/**
 * How far a world draws past its loaded chunks, for whoever fogs the view.
 * <p>
 * The haze is set on the view distance, and it saturates a little past it: that is right when the
 * loaded chunks are all there is, and it drowns anything drawn further out. A world that draws a
 * distant terrain publishes this in the context, and the haze stretches to it — starting a couple of
 * chunks before the edge of the loaded ground, so that the edge is under a veil rather than a line.
 */
public interface DistantReach {

    /** How far along the surface anything is drawn, in blocks; zero when nothing is drawn beyond the chunks. */
    float reach();

    /** Where the loaded chunks stop and the distant terrain takes over, in blocks from the camera. */
    float frontier();
}
