// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.sphere;

import org.terasology.context.annotation.API;
import org.terasology.engine.rendering.world.ChunkVisibilityPolicy;

/**
 * Implemented by a world generator whose world is not laid out flat.
 * <p>
 * The two answers come together on purpose: a world that is drawn bent must also be culled bent, and
 * a generator that supplied one without the other would leave the renderer half convinced.
 */
@API
public interface SphereProjectionProvider {

    SphereProjection getSphereProjection();

    ChunkVisibilityPolicy getChunkVisibilityPolicy();
}
