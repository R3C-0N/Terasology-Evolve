// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.engine.network.Replicate;
import org.terasology.gestalt.entitysystem.component.Component;

/**
 * On an item: the creature it puts into the world when used on a surface.
 * <p>
 * The design calls these spawn totems — a socle and a pillar carrying the creature's own head. The training
 * ground is the exception it states outright: there the object <em>is</em> the creature, so the mannequin's
 * item carries its own prefab and looks like what it poses.
 */
public class SpawnerComponent implements Component<SpawnerComponent> {
    @Replicate
    public String creature;

    @Override
    public void copyFrom(SpawnerComponent other) {
        this.creature = other.creature;
    }
}
