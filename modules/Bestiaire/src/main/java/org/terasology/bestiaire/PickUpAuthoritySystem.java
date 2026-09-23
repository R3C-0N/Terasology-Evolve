// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.engine.entitySystem.entity.EntityManager;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.event.EventPriority;
import org.terasology.engine.entitySystem.event.Priority;
import org.terasology.engine.entitySystem.prefab.Prefab;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterMode;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.logic.characters.events.AttackEvent;
import org.terasology.engine.logic.inventory.ItemComponent;
import org.terasology.engine.registry.In;
import org.terasology.engine.utilities.Assets;
import org.terasology.gestalt.entitysystem.event.ReceiveEvent;
import org.terasology.module.inventory.systems.InventoryManager;

/**
 * Picking a creature back up, bare-handed.
 * <p>
 * Only creatures marked {@code pickable} answer to it, which today means the training ground's alone:
 * there the object IS the creature. A totem is wood and stone and not the animal, so punching a mouflon
 * cannot pocket it without the same gesture meaning two things depending on what it lands on.
 * <p>
 * "Se ramasse à la main" is the design's own wording, and bare-handed is the whole test: anything held that is
 * an item — a pickaxe, a sword, a stack of dirt — hits instead. That keeps one gesture for two intents without
 * a mode, and it is why this runs at high priority and consumes the event: at default priority the damage
 * chain of {@code Health} would already have taken its bite.
 */
@RegisterSystem(RegisterMode.AUTHORITY)
public class PickUpAuthoritySystem extends BaseComponentSystem {

    @In
    private EntityManager entityManager;

    @In
    private InventoryManager inventoryManager;

    @Priority(EventPriority.PRIORITY_HIGH)
    @ReceiveEvent
    public void pickUp(AttackEvent event, EntityRef creature, CreatureComponent component) {
        if (!component.pickable || component.item == null || component.item.isEmpty()) {
            return;
        }
        if (event.getDirectCause().hasComponent(ItemComponent.class)) {
            return;
        }
        Prefab prefab = Assets.getPrefab(component.item).orElse(null);
        if (prefab == null) {
            return;
        }

        EntityRef character = event.getInstigator();
        EntityRef item = entityManager.create(prefab);
        if (!inventoryManager.giveItem(character, character, item)) {
            // A full bag leaves the creature standing: dropping it on the floor would need a pickup entity,
            // and losing it outright would be worse than not picking it up.
            item.destroy();
            return;
        }
        creature.destroy();
        event.consume();
    }
}
