// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.engine.network.Replicate;
import org.terasology.gestalt.entitysystem.component.Component;

/**
 * Marks an entity as one of the bestiary's creatures.
 * <p>
 * The species is the identifier the design project uses ({@code mannequin}, {@code mouflon}, …), and it is
 * what the console commands and, later, the bestiary screen look a creature up by. The item is the object the
 * creature comes out of — its spawn totem, or, for the training ground, the creature itself as an object.
 */
public class CreatureComponent implements Component<CreatureComponent> {
    @Replicate
    public String species;

    /** Prefab of the object this creature comes out of; empty means no object exists for it. */
    @Replicate
    public String item = "";

    /**
     * Whether a bare hand puts the creature back into that object.
     * <p>
     * This is the training ground's exception and nothing else. Elsewhere the object is a totem — a thing of
     * wood and stone that is <em>not</em> the animal — so punching a mouflon could not pocket it without the
     * gesture meaning two different things depending on what you hit.
     */
    @Replicate
    public boolean pickable;

    @Override
    public void copyFrom(CreatureComponent other) {
        this.species = other.species;
        this.item = other.item;
        this.pickable = other.pickable;
    }
}
