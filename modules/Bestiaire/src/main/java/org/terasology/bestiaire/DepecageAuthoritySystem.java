// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterMode;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.logic.characters.events.AttackEvent;
import org.terasology.engine.logic.health.DoDestroyEvent;
import org.terasology.engine.logic.health.EngineDamageTypes;
import org.terasology.gestalt.entitysystem.event.ReceiveEvent;

/**
 * Opening a carcass, with a knife.
 * <p>
 * <strong>The loot table is read by somebody else.</strong> {@code Drops} already owns the grammar —
 * {@code chance|min-max*urn}, evaluated entry by entry — and {@code DropGrammarSystem} already turns it into
 * objects on the ground, on {@code DoDestroyEvent}. Nothing in that system supposes a block: it asks for a
 * drop table and a place, and a carcass has both. So skinning is one event sent by hand and one body
 * destroyed, and there is no second parser to keep in step with the first.
 * <p>
 * <strong>Not {@code droppedWithTool}.</strong> The same module offers a tool-conditioned table, but its key
 * is a block material category read off {@code BlockDamageModifierComponent.materialDamageMultiplier} —
 * wiring a knife into it would mean inventing a damage-type prefab whose only purpose is to carry a word
 * that matches. The knife is tested here instead, and the destroy event is only sent once it has passed.
 * <p>
 * <strong>A bare hand does nothing at all, and that is not written anywhere.</strong> The corpse lost its
 * {@code HealthComponent} the moment it died, so {@code DamageAuthoritySystem} no longer answers for it: a
 * blow without a blade lands on a thing that cannot be hurt, and falls through. Nothing to consume, no
 * priority to claim.
 */
@RegisterSystem(RegisterMode.AUTHORITY)
public class DepecageAuthoritySystem extends BaseComponentSystem {

    @ReceiveEvent
    public void depecer(AttackEvent event, EntityRef cadavre, CadavreComponent etat) {
        if (!event.getDirectCause().hasComponent(DepeceurComponent.class)) {
            return;
        }
        // L'ordre est tout : le butin naît du DoDestroyEvent, qui est synchrone, donc il est au sol avant
        // que le corps ne parte. Détruire d'abord ne laisserait qu'une entité morte à qui parler.
        cadavre.send(new DoDestroyEvent(event.getInstigator(), event.getDirectCause(),
                EngineDamageTypes.DIRECT.get()));
        cadavre.destroy();
        event.consume();
    }
}
