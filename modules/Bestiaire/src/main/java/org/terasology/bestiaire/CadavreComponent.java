// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.engine.network.Replicate;
import org.terasology.gestalt.entitysystem.component.Component;

/**
 * What is left of a creature that has been killed, until somebody skins it or the ground takes it.
 * <p>
 * The corpse <em>is</em> the creature: {@link CadavreAuthoritySystem} consumes the destruction and strips the
 * entity of everything that made it alive, rather than spawning a body of its own. That is what keeps the
 * mesh, the material and the place — and it is why there is no corpse prefab per species to write, nineteen
 * times.
 * <p>
 * The species is kept for the console and for reading a save; nothing in the loot depends on it, because the
 * loot table rides along on the same entity. What the corpse does <em>not</em> keep is
 * {@link CreatureComponent}: gravity, the census and the forgetting radius all key off it, and a body that
 * still counted as wildlife would hold a herd's ceiling down for five minutes and be swept away as soon as
 * the player walked off.
 */
public class CadavreComponent implements Component<CadavreComponent> {

    /** The species it was, for {@code FauneCommands} and for reading a save. */
    @Replicate
    public String espece = "";

    /**
     * Blocks per second the body settles into the ground.
     * <p>
     * Sized so that the whole of it is under the floor by the time the five minutes are up: a rabbit is gone
     * long before a bear looks buried. It is not replicated because it is never read by a client — only the
     * position it produces is.
     */
    public float enfoncement;

    @Override
    public void copyFrom(CadavreComponent other) {
        this.espece = other.espece;
        this.enfoncement = other.enfoncement;
    }
}
