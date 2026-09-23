// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.engine.network.Replicate;
import org.terasology.gestalt.entitysystem.component.Component;

/**
 * Marks an entity as one of the bestiary's creatures.
 * <p>
 * The species is the identifier the design project uses ({@code mannequin}, {@code loup}, …), and it is what
 * the console commands and, later, the bestiary screen look a creature up by. The item is what the creature
 * goes back into when picked up bare-handed — the spawn totem it came from, or, for the training ground, the
 * creature itself as an object.
 */
public class CreatureComponent implements Component<CreatureComponent> {
    @Replicate
    public String species;

    /** Prefab of the item given back on pick-up; empty means the creature cannot be picked up. */
    @Replicate
    public String item = "";

    @Override
    public void copyFrom(CreatureComponent other) {
        this.species = other.species;
        this.item = other.item;
    }
}
