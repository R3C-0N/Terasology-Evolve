// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import com.google.common.collect.Lists;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.terasology.drops.grammar.DropGrammarComponent;
import org.terasology.engine.entitySystem.entity.EntityManager;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterMode;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.entitySystem.systems.UpdateSubscriberSystem;
import org.terasology.engine.logic.delay.DelayManager;
import org.terasology.engine.logic.delay.DelayedActionTriggeredEvent;
import org.terasology.engine.logic.health.BeforeDestroyEvent;
import org.terasology.engine.logic.location.LocationComponent;
import org.terasology.engine.physics.CollisionGroup;
import org.terasology.engine.physics.StandardCollisionGroup;
import org.terasology.engine.physics.components.RigidBodyComponent;
import org.terasology.engine.registry.In;
import org.terasology.engine.rendering.logic.SkeletalMeshComponent;
import org.terasology.gestalt.entitysystem.event.ReceiveEvent;
import org.terasology.module.health.components.HealthComponent;
import org.terasology.module.health.components.RegenComponent;
import org.terasology.module.health.core.BaseRegenComponent;

import java.util.List;

/**
 * A killed beast lies where it fell, and the ground takes it back.
 * <p>
 * <strong>The corpse is the creature, not a copy of it.</strong> The engine's destruction runs
 * {@code BeforeDestroyEvent} → {@code DoDestroyEvent} → {@code destroy()}, and the first of the three is
 * consumable: consuming it leaves the entity standing, with its mesh, its material, its place and — this is
 * the point — its loot table. A corpse prefab per species would be nineteen files to keep in step with
 * nineteen creatures, and every one of them a chance to forget one.
 * <p>
 * What has to go is everything that made it alive, and each removal pays for itself:
 * <ul>
 *     <li>{@link HealthComponent}, {@link BaseRegenComponent} and {@link RegenComponent} — a body left at zero health does not stay
 *     at zero. Regeneration would stand it back up within the minute.</li>
 *     <li>{@link WanderComponent}, {@link PredatorComponent}, {@link ChaseComponent},
 *     {@link GrazeComponent}, {@link HomeComponent} — the legs, the appetite and the reasons to use them.
 *     Removing them beats testing a flag in three systems, which is three places to forget.</li>
 *     <li>{@link CreatureComponent} — {@link GravityAuthoritySystem} runs on it, and gravity holds a body
 *     <em>on</em> the floor, which is exactly what sinking is not.</li>
 *     <li>{@link SauvageComponent} and {@link HabitatComponent} — {@link FauneAuthoritySystem} counts the
 *     first against the herd's ceiling and sweeps it away past the forgetting radius. A carcass that kept
 *     them would hold a meadow empty for five minutes and vanish the moment the player turned round.</li>
 * </ul>
 * <p>
 * <strong>Only what carries loot leaves a body.</strong> The test is the presence of a
 * {@link DropGrammarComponent}, not a list of species: a carcass nobody can skin is litter, and the training
 * dummy and the champion have no table by design.
 * <p>
 * <strong>It has to be walked through and still be hit.</strong> The blow that skins it arrives through
 * {@code physics.rayTrace}, and Bullet lets a ray through only when the body's own mask is non-empty — so
 * emptying {@code collidesWith} would make the corpse unskinnable, not just soft. Leaving {@code WORLD}
 * alone in it keeps the ray and drops the character: the pair test needs the mask to name the other's group,
 * and a corpse that no longer answers to {@code CHARACTER} is one a player walks into without stopping.
 */
@RegisterSystem(RegisterMode.AUTHORITY)
public class CadavreAuthoritySystem extends BaseComponentSystem implements UpdateSubscriberSystem {

    /** How long a carcass lies there before the ground has it, in seconds. */
    private static final float DUREE = 300f;

    private static final String POURRITURE = "Bestiaire:pourriture";

    /** How far under its own feet a body has to go before it reads as gone. */
    private static final float MARGE = 0.5f;

    private static final List<CollisionGroup> TRAVERSABLE =
            Lists.<CollisionGroup>newArrayList(StandardCollisionGroup.WORLD);

    @In
    private EntityManager entityManager;

    @In
    private DelayManager delayManager;

    /**
     * The blow that killed it is the one that lays it down.
     * <p>
     * No priority is asked for: {@code EntityDestructionAuthoritySystem} reads {@code isConsumed()} after
     * every receiver has run, so being heard at all is enough.
     */
    @ReceiveEvent
    public void onMort(BeforeDestroyEvent event, EntityRef bete, CreatureComponent creature) {
        if (!bete.hasComponent(DropGrammarComponent.class) || bete.hasComponent(CadavreComponent.class)) {
            return;
        }
        LocationComponent location = bete.getComponent(LocationComponent.class);
        if (location == null) {
            return;
        }
        event.consume();

        CadavreComponent cadavre = new CadavreComponent();
        cadavre.espece = creature.species;
        cadavre.enfoncement = (Stride.demiHauteur(bete) * 2f + MARGE) / DUREE;

        bete.removeComponent(HealthComponent.class);
        bete.removeComponent(BaseRegenComponent.class);
        bete.removeComponent(RegenComponent.class);
        bete.removeComponent(WanderComponent.class);
        bete.removeComponent(PredatorComponent.class);
        bete.removeComponent(ChaseComponent.class);
        bete.removeComponent(GrazeComponent.class);
        bete.removeComponent(HomeComponent.class);
        bete.removeComponent(AccrocheComponent.class);
        bete.removeComponent(HabitatComponent.class);
        bete.removeComponent(SauvageComponent.class);
        bete.removeComponent(CreatureComponent.class);
        // Tuee en plein vol : le recul ne tiendra pas ses promesses, faute de systeme pour le finir. Le corps
        // se reprend ici, sans quoi il resterait dynamique et Bullet se disputerait l'enfoncement.
        bete.removeComponent(ReculComponent.class);
        bete.addComponent(cadavre);

        coucher(bete, location);
        traverser(bete);
        eclairer(bete, location);
        delayManager.addDelayedAction(bete, POURRITURE, (long) (DUREE * 1000f));
    }

    /**
     * The body settles, at the pace of its own size.
     * <p>
     * There is no death animation to play — the nineteen skeletons carry one clip each, {@code repos} — so
     * the fall on its side and this slow descent are the whole of what says "dead". It is the walking
     * creature's own cost: one position written per body per frame, and bodies are few and short-lived.
     */
    @Override
    public void update(float delta) {
        for (EntityRef cadavre : entityManager.getEntitiesWith(CadavreComponent.class, LocationComponent.class)) {
            CadavreComponent etat = cadavre.getComponent(CadavreComponent.class);
            LocationComponent location = cadavre.getComponent(LocationComponent.class);
            if (etat == null || location == null || etat.enfoncement <= 0f) {
                continue;
            }
            Vector3f position = location.getWorldPosition(new Vector3f());
            position.y -= etat.enfoncement * delta;
            location.setWorldPosition(position);
            cadavre.saveComponent(location);
        }
    }

    /** Five minutes on, whatever was not taken is gone with the body. */
    @ReceiveEvent
    public void onPourriture(DelayedActionTriggeredEvent event, EntityRef cadavre, CadavreComponent etat) {
        if (!POURRITURE.equals(event.getActionId())) {
            return;
        }
        cadavre.destroy();
    }

    /** On its side, keeping the way it was facing: a roll about its own forward axis. */
    private void coucher(EntityRef bete, LocationComponent location) {
        Quaternionf rotation = location.getWorldRotation(new Quaternionf());
        location.setWorldRotation(rotation.rotateZ((float) (Math.PI / 2f)));
        bete.saveComponent(location);
    }

    /**
     * The body keeps the light it died in.
     * <p>
     * Sinking is a piece of staging, not a move to somewhere darker: a carcass lit where it actually is
     * samples the inside of a block the moment its centre passes under the grass, finds no light at all, and
     * turns into a black silhouette lying in a sunlit field. So the sample point is pinned, once, to where
     * the beast stood when it fell — at its own mid-height, which is air, not the ground it was standing on.
     * The body then goes on taking the day and the night like everything above the surface, because the
     * daylight factor is a function of the hour and not of the place.
     */
    private void eclairer(EntityRef bete, LocationComponent location) {
        SkeletalMeshComponent maillage = bete.getComponent(SkeletalMeshComponent.class);
        if (maillage == null) {
            return;
        }
        Vector3f position = location.getWorldPosition(new Vector3f());
        position.y += Stride.demiHauteur(bete);
        maillage.lightPosition = position;
        bete.saveComponent(maillage);
    }

    private void traverser(EntityRef bete) {
        RigidBodyComponent corps = bete.getComponent(RigidBodyComponent.class);
        if (corps == null) {
            return;
        }
        corps.collidesWith = Lists.newArrayList(TRAVERSABLE);
        // Cinematique quoi qu'il arrive : l'enfoncement ecrit la position a la main, et un corps encore
        // dynamique d'un recul interrompu se la verrait reecrire par Bullet a chaque pas de simulation.
        corps.kinematic = true;
        bete.saveComponent(corps);
    }
}
