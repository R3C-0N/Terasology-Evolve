// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterMode;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.gestalt.entitysystem.event.ReceiveEvent;
import org.terasology.module.health.events.OnDamagedEvent;

/**
 * Turns damage taken by a creature into a number for every client to draw.
 */
@RegisterSystem(RegisterMode.AUTHORITY)
public class DamageNumberAuthoritySystem extends BaseComponentSystem {

    @ReceiveEvent
    public void onDamaged(OnDamagedEvent event, EntityRef creature, CreatureComponent component) {
        int amount = event.getDamageAmount();
        if (amount > 0) {
            creature.send(new DamageNumberEvent(amount));
        }
    }
}
