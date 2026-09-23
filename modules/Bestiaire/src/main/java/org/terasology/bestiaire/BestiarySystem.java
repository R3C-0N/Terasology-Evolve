// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.engine.entitySystem.prefab.Prefab;
import org.terasology.engine.entitySystem.prefab.PrefabManager;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.registry.In;
import org.terasology.engine.registry.Share;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The bestiary, built once at start-up.
 * <p>
 * Nothing is recomputed afterwards: a creature is a prefab, and prefabs do not appear while the game runs.
 */
@RegisterSystem
@Share(Bestiary.class)
public class BestiarySystem extends BaseComponentSystem implements Bestiary {

    @In
    private PrefabManager prefabManager;

    private final Map<String, Prefab> parEspece = new TreeMap<>();

    @Override
    public void initialise() {
        for (Prefab prefab : prefabManager.listPrefabs(CreatureComponent.class)) {
            CreatureComponent creature = prefab.getComponent(CreatureComponent.class);
            if (creature.species != null && !creature.species.isEmpty()) {
                parEspece.put(creature.species, prefab);
            }
        }
    }

    @Override
    public Collection<Prefab> creatures() {
        return List.copyOf(parEspece.values());
    }

    @Override
    public Optional<Prefab> creature(String species) {
        return Optional.ofNullable(parEspece.get(species));
    }

    @Override
    public Collection<String> species() {
        return List.copyOf(parEspece.keySet());
    }
}
