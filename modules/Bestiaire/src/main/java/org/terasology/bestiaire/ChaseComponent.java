// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.gestalt.entitysystem.component.Component;

/**
 * A marker: this creature has a prey right now, and its legs belong to {@link HuntAuthoritySystem}.
 * <p>
 * It carries nothing — the prey itself lives in that system's own state, beside the clocks, where it does not
 * have to survive a save. What the component buys is the one thing a private map cannot say out loud:
 * {@link WanderAuthoritySystem} skips whoever wears it. Without that, both systems would move the same animal
 * in the same frame, and a charging wolf would drift sideways with every random heading the stroll picked.
 * <p>
 * It is put on and taken off when a hunt starts and ends, never per frame, so its cost is the cost of a mood
 * changing.
 */
public class ChaseComponent implements Component<ChaseComponent> {
    @Override
    public void copyFrom(ChaseComponent other) {
    }
}
