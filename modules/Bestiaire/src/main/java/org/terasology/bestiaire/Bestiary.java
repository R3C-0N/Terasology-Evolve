// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.context.annotation.API;
import org.terasology.engine.entitySystem.prefab.Prefab;

import java.util.Collection;
import java.util.Optional;

/**
 * Every creature the game knows, by species.
 * <p>
 * Read at start-up from the prefabs that carry a {@link CreatureComponent}, the way {@code CatalogSystem}
 * reads the creative catalogue: adding a creature is adding a prefab, never a line here. The console commands
 * use it today, and the bestiary screen will use the same list.
 */
@API
public interface Bestiary {

    Collection<Prefab> creatures();

    Optional<Prefab> creature(String species);

    /** Species names, sorted, for a message that has to tell the player what exists. */
    Collection<String> species();
}
