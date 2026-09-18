// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.world.generator;

import org.joml.Vector2ic;
import org.terasology.context.annotation.API;

/**
 * Implemented by a {@link WorldGenerator} whose world is not centred on the origin.
 * <p>
 * The world preview frames a square around (0, 0), which is the right guess for a generator that
 * spreads outwards from there — most of them. A generator that lays its world out somewhere else
 * gets a preview of whatever happens to be at the origin instead, and for a world that does not
 * reach the origin at all that is an empty square at every zoom the slider can reach.
 * <p>
 * The centre is asked for once, when the preview is built, and it must be cheap: a constant of the
 * world's layout rather than anything that has to be searched for. In particular it should not be
 * the spawn point, which may cost a search of its own.
 */
@API
public interface PreviewCentered {

    /**
     * @return the world column the preview should be framed on
     */
    Vector2ic getPreviewCentre();
}
