// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.joml.Vector3f;
import org.terasology.engine.entitySystem.entity.EntityManager;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterMode;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.entitySystem.systems.UpdateSubscriberSystem;
import org.terasology.engine.logic.location.LocationComponent;
import org.terasology.engine.physics.components.RigidBodyComponent;
import org.terasology.engine.physics.events.ChangeVelocityEvent;
import org.terasology.engine.physics.events.ImpulseEvent;
import org.terasology.engine.registry.In;
import org.terasology.engine.world.WorldProvider;
import org.terasology.gestalt.entitysystem.event.ReceiveEvent;
import org.terasology.module.health.components.HealthComponent;
import org.terasology.module.health.events.OnDamagedEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * A struck beast goes backwards, and Bullet is the one that throws it.
 * <p>
 * <strong>Kinematic is a state here, not a property.</strong> A creature's body is kinematic at rest, which
 * is what lets {@link Stride} and {@link GravityAuthoritySystem} walk it by writing its position: Bullet
 * reads a kinematic body's transform off the entity and never writes it back. That is also exactly why a
 * blow used to move nothing. So the blow hands the body over — the flag comes off, an impulse goes in, and
 * for the length of the shove the creature is an ordinary falling object that slides on slopes and stops at
 * walls. When the clock runs out the body is taken back, set down on the ground and made kinematic again.
 * The alternative — dynamic bodies at all times — would mean rewriting walking, climbing a step, rounding a
 * rock and the carcass's descent in forces, and re-tuning nineteen creatures.
 * <p>
 * <strong>Every system that walks a creature stands aside while {@link ReculComponent} is on it.</strong>
 * Two owners of one position do not share it, they tear at it — the same reason {@link ChaseComponent} keeps
 * {@link WanderAuthoritySystem} off a hunting wolf. Gravity stands aside too, Bullet's own being better than
 * the hand-written one for a body in the air.
 * <p>
 * <strong>The killing blow does not throw.</strong> A carcass is made out of the creature itself, in the
 * middle of the destruction it consumes, and it has a five-minute descent of its own to run; handing that
 * body to Bullet at the same instant would make two things own it. A beast therefore dies on the spot.
 * <p>
 * <strong>The push is measured against the creature, not against the weapon alone.</strong> A rabbit flies
 * and a bear shifts, because the speed is divided by the beast's own half-height; the weapon then adds to
 * it, so a mallet throws further than a knife without a second table to keep in step with the first.
 */
@RegisterSystem(RegisterMode.AUTHORITY)
public class ReculAuthoritySystem extends BaseComponentSystem implements UpdateSubscriberSystem {

    /**
     * How long the body belongs to Bullet, in seconds.
     * <p>
     * Shortened from 0.4 s, which read as a slow slide rather than a blow. The push went up by the
     * same factor so the distance is unchanged: the beast ends up where it used to, it simply gets
     * there faster — which is what makes a hit feel like a hit.
     */
    private static final float DUREE = 0.28f;

    /** Backward speed given to a creature of the reference size, in blocks per second. */
    private static final float POUSSEE = 2.9f;

    /** The half-height that speed is calibrated on: a wolf. */
    private static final float ETALON = 0.5f;

    /** Share of the push that goes upwards. Enough to unstick the feet, not enough to launch. */
    private static final float ENVOL = 0.25f;

    /** Extra speed per point of damage, as a fraction of {@link #POUSSEE}. */
    private static final float PART_DEGATS = 0.02f;

    /** Neither a mouse nor a mountain: what the size and the weapon together are allowed to do. */
    private static final float MOINDRE = 0.4f;
    private static final float PLUS = 2f;

    /**
     * How far the landing is allowed to correct the height, in blocks.
     * <p>
     * Beyond it the beast is over a hole or a cliff, and the floor found below is not a rounding error but a
     * fall to be made. Snapping to it would drop the animal four blocks in one frame — measured, the first
     * wolf shoved towards the shore arrived on the beach by teleport.
     */
    private static final float RATTRAPAGE = 0.6f;

    private static final Vector3f LIBRE = new Vector3f(1f, 1f, 1f);
    private static final Vector3f BLOQUE = new Vector3f();

    @In
    private EntityManager entityManager;

    @In
    private WorldProvider worldProvider;

    /**
     * Whether something else is holding this body up. Read by everything that would otherwise walk it.
     */
    static boolean enCours(EntityRef creature) {
        return creature.hasComponent(ReculComponent.class);
    }

    @ReceiveEvent
    public void onDamaged(OnDamagedEvent event, EntityRef bete, CreatureComponent creature) {
        if (bete.hasComponent(ScelleComponent.class) || bete.hasComponent(CadavreComponent.class)) {
            return;
        }
        HealthComponent sante = bete.getComponent(HealthComponent.class);
        if (sante == null || sante.currentHealth <= 0) {
            // Le coup qui tue ne projette pas : le cadavre nait dans la destruction meme, et il a sa propre
            // descente a mener. Deux proprietaires pour un corps, c'est un corps qui tressaute.
            return;
        }
        LocationComponent location = bete.getComponent(LocationComponent.class);
        RigidBodyComponent corps = bete.getComponent(RigidBodyComponent.class);
        if (location == null || corps == null) {
            return;
        }

        Vector3f fuite = ecarter(bete, location, event.getInstigator());
        float vitesse = allure(bete, event.getDamageAmount());

        corps.kinematic = false;
        // Elle glisse, elle ne culbute pas : un loup qui part en tonneau se lit comme un bug, et il faudrait
        // en plus le redresser avant de le rendre au sol.
        corps.angularFactor = new Vector3f(BLOQUE);
        ReculComponent recul = bete.getComponent(ReculComponent.class);
        if (recul == null) {
            recul = new ReculComponent();
            recul.reste = DUREE;
            bete.addComponent(recul);
        } else {
            recul.reste = DUREE;
            bete.saveComponent(recul);
        }
        bete.saveComponent(corps);

        bete.send(new ChangeVelocityEvent(new Vector3f(), new Vector3f()));
        bete.send(new ImpulseEvent(new Vector3f(fuite.x * vitesse, ENVOL * vitesse, fuite.z * vitesse)
                .mul(corps.mass)));
    }

    /**
     * The clock, and the landing.
     * <p>
     * The list is copied before it is walked: taking the body back removes the component the iteration is
     * built on.
     */
    @Override
    public void update(float delta) {
        List<EntityRef> voltigeurs = new ArrayList<>();
        entityManager.getEntitiesWith(ReculComponent.class).forEach(voltigeurs::add);

        for (EntityRef bete : voltigeurs) {
            ReculComponent recul = bete.getComponent(ReculComponent.class);
            if (recul == null) {
                continue;
            }
            recul.reste -= delta;
            if (recul.reste > 0f) {
                bete.saveComponent(recul);
                continue;
            }
            poser(bete);
        }
    }

    /**
     * Takes the body back: still, upright, kinematic, and set down on the floor it ended above.
     * <p>
     * The landing is not cosmetic. Bullet leaves a box resting <em>on</em> the ground by its own contact
     * margin, and the walking code measures the feet against {@link Ground#under}; handing back a creature
     * that the two disagree about makes it sink or hover for as long as it lives. It corrects a margin and
     * not a fall: a beast still in the air over lower ground is simply handed to
     * {@link GravityAuthoritySystem}, whose whole job is to bring it down at a speed the eye can follow.
     */
    private void poser(EntityRef bete) {
        bete.send(new ChangeVelocityEvent(new Vector3f(), new Vector3f()));

        RigidBodyComponent corps = bete.getComponent(RigidBodyComponent.class);
        if (corps != null) {
            corps.kinematic = true;
            corps.angularFactor = new Vector3f(LIBRE);
            bete.saveComponent(corps);
        }

        LocationComponent location = bete.getComponent(LocationComponent.class);
        if (location != null) {
            Vector3f position = location.getWorldPosition(new Vector3f());
            float pieds = position.y - Stride.demiHauteur(bete);
            float sol = Ground.under(worldProvider, position.x, pieds, position.z);
            if (!Float.isNaN(sol) && Math.abs(sol - pieds) <= RATTRAPAGE) {
                position.y += sol - pieds;
                location.setWorldPosition(position);
                bete.saveComponent(location);
            }
        }
        bete.removeComponent(ReculComponent.class);
    }

    /**
     * Away from whoever struck, flat. A blow from straight above has no direction to give, and the beast then
     * goes out the way it is facing — anything rather than a zero vector, which would leave it hanging in the
     * air for half a second doing nothing.
     */
    private Vector3f ecarter(EntityRef bete, LocationComponent location, EntityRef frappeur) {
        Vector3f fuite = new Vector3f();
        LocationComponent depuis = frappeur.getComponent(LocationComponent.class);
        if (depuis != null) {
            location.getWorldPosition(fuite).sub(depuis.getWorldPosition(new Vector3f()));
        }
        fuite.y = 0f;
        if (fuite.lengthSquared() < 1e-4f) {
            location.getWorldDirection(fuite);
            fuite.y = 0f;
        }
        if (fuite.lengthSquared() < 1e-4f) {
            fuite.set(0f, 0f, 1f);
        }
        return fuite.normalize();
    }

    /** The push itself: smaller beasts go further, and a heavier blow adds to it. */
    private float allure(EntityRef bete, int degats) {
        float taille = ETALON / Math.max(0.1f, Stride.demiHauteur(bete));
        float arme = 1f + Math.max(0, degats) * PART_DEGATS;
        return POUSSEE * Math.max(MOINDRE, Math.min(PLUS, taille * arme));
    }
}
