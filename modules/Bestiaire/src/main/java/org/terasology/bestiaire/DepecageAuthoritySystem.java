// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterMode;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.logic.common.ActivateEvent;
import org.terasology.engine.logic.health.DoDestroyEvent;
import org.terasology.engine.logic.health.EngineDamageTypes;
import org.terasology.gestalt.entitysystem.event.ReceiveEvent;

/**
 * Opening a carcass, with a knife, on the right button.
 * <p>
 * <strong>A carcass cannot be destroyed, only opened or waited out.</strong> It lost its
 * {@link org.terasology.module.health.components.HealthComponent} the moment it died, so the damage system
 * no longer answers for it and no blow of any kind can touch it — hitting one bare-handed, with an axe or
 * with a knife does nothing at all. The two ways out are this one and the five-minute clock in
 * {@link CadavreAuthoritySystem}, and there is no third.
 * <p>
 * <strong>Skinning is a use, not a blow, and that is why it moved to the right button.</strong> The left
 * button is the attack and it swings at whatever is in front — it cannot tell the gesture of taking a hide
 * from the gesture of killing, and a player finishing a beast off would have skinned it with the follow-up
 * swing. The right button carries an intention: {@link ActivateEvent} reaches the held item with the aimed
 * entity attached, so the knife is the receiver and the carcass is {@link ActivateEvent#getTarget()}. The
 * engine has already checked, at the authority, that the target really is under the crosshair and within
 * reach.
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
 * that matches. The knife is tested here instead, by the component the eleven knife prefabs carry, and the
 * destroy event is only sent once it has passed.
 */
@RegisterSystem(RegisterMode.AUTHORITY)
public class DepecageAuthoritySystem extends BaseComponentSystem {

    @ReceiveEvent
    public void depecer(ActivateEvent event, EntityRef couteau, DepeceurComponent lame) {
        EntityRef cadavre = event.getTarget();
        if (!cadavre.hasComponent(CadavreComponent.class)) {
            return;
        }
        // L'ordre est tout : le butin nait du DoDestroyEvent, qui est synchrone, donc il est au sol avant
        // que le corps ne parte. Detruire d'abord ne laisserait qu'une entite morte a qui parler.
        cadavre.send(new DoDestroyEvent(event.getInstigator(), couteau, EngineDamageTypes.DIRECT.get()));
        cadavre.destroy();
        event.consume();
    }
}
