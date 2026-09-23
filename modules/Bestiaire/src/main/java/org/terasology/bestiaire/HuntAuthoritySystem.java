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
import org.terasology.engine.logic.characters.AliveCharacterComponent;
import org.terasology.engine.logic.characters.CharacterComponent;
import org.terasology.engine.logic.health.EngineDamageTypes;
import org.terasology.engine.logic.location.LocationComponent;
import org.terasology.engine.registry.In;
import org.terasology.engine.world.WorldProvider;
import org.terasology.gestalt.entitysystem.event.ReceiveEvent;
import org.terasology.module.health.events.DoDamageEvent;
import org.terasology.module.health.events.OnDamagedEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * A hostile creature picks a prey, runs it down, and bites.
 * <p>
 * This is the second half of the bestiary's two temperaments, and it is a separate system because it answers
 * a question the first one never asks: <em>whom</em>. {@link WanderAuthoritySystem} has a heading and a
 * clock; a hunt has a target, and a target has to be found, kept, judged out of reach and let go. The legs
 * are the same legs, and they live in {@link Stride} so that neither system owns them.
 * <p>
 * <strong>It hunts characters, and only those who answer for it.</strong> Every candidate is asked with a
 * {@link BeforeHuntedEvent} before it becomes a prey, which is what keeps a wolf off someone who is building
 * rather than surviving. The question is repeated at every sniff, so leaving creative mode puts you back on
 * the menu within the moment, and entering it takes you off.
 * <p>
 * <strong>There is no path, only a heading.</strong> When the ground refuses a step the creature tries the
 * same heading swung aside, further and further, and takes the first that carries — which is enough to get
 * round a rock or a tree, and is not enough to get round a house. Real pathfinding is a chantier of its own
 * and it is not this one; what is here is a creature that looks like it wants to reach you, and that a wall
 * still defeats.
 */
@RegisterSystem(RegisterMode.AUTHORITY)
public class HuntAuthoritySystem extends BaseComponentSystem implements UpdateSubscriberSystem {

    /** Seconds between two sweeps of the surroundings. */
    private static final float FLAIR = 0.4f;

    /**
     * How far the heading is swung aside when the way is barred, in radians, tried in this order.
     * <p>
     * Both signs of each angle, near before far: the creature keeps as much of its intent as the ground
     * allows. A single fixed sidestep would make every wolf slide round every obstacle the same way, and two
     * of them meeting a tree would tread the same line.
     */
    private static final float[] ECARTS = {0f, 0.6f, -0.6f, 1.2f, -1.2f, 1.9f, -1.9f};

    @In
    private EntityManager entityManager;

    @In
    private WorldProvider worldProvider;

    private final Map<EntityRef, Chasse> chasses = new HashMap<>();

    /** One hunter's state: whom it is after, where it is looking, and its two clocks. */
    private static final class Chasse {
        private EntityRef proie = EntityRef.NULL;
        private float corps;            // where the body points, in radians
        private float flair;            // seconds before the surroundings are swept again
        private float morsure;          // seconds before the next bite
    }

    @Override
    public void update(float delta) {
        chasses.keySet().removeIf(entity -> !entity.exists());

        for (EntityRef creature : entityManager.getEntitiesWith(PredatorComponent.class,
                LocationComponent.class)) {
            PredatorComponent predator = creature.getComponent(PredatorComponent.class);
            LocationComponent location = creature.getComponent(LocationComponent.class);
            if (predator == null || location == null) {
                continue;
            }
            Chasse chasse = chasses.computeIfAbsent(creature, e -> naitre(location));
            chasse.flair -= delta;
            chasse.morsure -= delta;

            Vector3f position = location.getWorldPosition(new Vector3f());
            if (chasse.flair <= 0f) {
                chasse.flair = FLAIR;
                chasse.proie = juger(chasse.proie, position, predator);
            }
            if (!chasse.proie.exists()) {
                marquer(creature, false);
                continue;
            }
            marquer(creature, true);
            poursuivre(creature, predator, location, position, chasse, delta);
        }
    }

    /**
     * Striking a hostile creature does not frighten it, it introduces you.
     * <p>
     * {@link WanderAuthoritySystem} sends a struck animal running, and it leaves this one alone for exactly
     * that reason. A wolf that bolted from the first blow could never be fought, only chased away — and the
     * bestiary's word for it is not "shy".
     */
    @ReceiveEvent
    public void onDamaged(OnDamagedEvent event, EntityRef creature, PredatorComponent predator) {
        LocationComponent location = creature.getComponent(LocationComponent.class);
        EntityRef frappeur = event.getInstigator();
        if (location == null || !chassable(frappeur)) {
            return;
        }
        Chasse chasse = chasses.computeIfAbsent(creature, e -> naitre(location));
        chasse.proie = frappeur;
        chasse.flair = FLAIR;
        marquer(creature, true);
    }

    private Chasse naitre(LocationComponent location) {
        Chasse chasse = new Chasse();
        Vector3f devant = location.getWorldDirection(new Vector3f());
        chasse.corps = (float) Math.atan2(devant.x, devant.z);
        return chasse;
    }

    /**
     * Keeps the prey if it is still worth having, otherwise looks for the nearest one in sight.
     * <p>
     * A prey already held is kept out to {@link PredatorComponent#giveUp} and never swapped for a closer one:
     * a creature that changed its mind every time someone walked past would stand between two people and
     * follow neither.
     * <p>
     * {@link PredatorComponent#onlyWhenStruck} stops before the sweep, not before the keeping: a bear that
     * has been woken hunts exactly like a wolf, and lets go at the same distance.
     */
    private EntityRef juger(EntityRef proie, Vector3f position, PredatorComponent predator) {
        if (chassable(proie) && portee(proie, position, predator) <= predator.giveUp) {
            return proie;
        }
        if (predator.onlyWhenStruck) {
            // Une bete qui attend d'etre frappee ne balaye pas ses alentours : elle n'a de proie que celle
            // que `onDamaged` lui donne, et la garde jusqu'a `giveUp` comme les autres.
            return EntityRef.NULL;
        }
        EntityRef trouvee = EntityRef.NULL;
        float plusProche = predator.sight;
        for (EntityRef candidat : entityManager.getEntitiesWith(CharacterComponent.class,
                LocationComponent.class)) {
            float distance = portee(candidat, position, predator);
            if (distance <= plusProche && chassable(candidat)) {
                plusProche = distance;
                trouvee = candidat;
            }
        }
        return trouvee;
    }

    /**
     * Horizontal distance, or infinity when the candidate is too far above or below to be worth a thought.
     * <p>
     * Horizontal on purpose: a creature stands on the ground and so does what it hunts, and measuring the
     * slant would make it give up on someone standing on a hillock. The vertical limit is a separate, coarser
     * question — a prey on a roof is not a prey.
     */
    private float portee(EntityRef cible, Vector3f position, PredatorComponent predator) {
        LocationComponent location = cible.getComponent(LocationComponent.class);
        if (location == null) {
            return Float.POSITIVE_INFINITY;
        }
        Vector3f but = location.getWorldPosition(new Vector3f());
        if (!but.isFinite() || Math.abs(but.y - position.y) > predator.reachUp) {
            return Float.POSITIVE_INFINITY;
        }
        float dx = but.x - position.x;
        float dz = but.z - position.z;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    /** Whether this is a living character that has not been excused from being hunted. */
    private boolean chassable(EntityRef cible) {
        if (!cible.exists()
                || !cible.hasComponent(CharacterComponent.class)
                || !cible.hasComponent(AliveCharacterComponent.class)
                || !cible.hasComponent(LocationComponent.class)) {
            return false;
        }
        BeforeHuntedEvent question = new BeforeHuntedEvent();
        cible.send(question);
        return !question.isConsumed();
    }

    /** One frame of a chase: turn towards the prey, close the gap, and bite once in reach. */
    private void poursuivre(EntityRef creature, PredatorComponent predator, LocationComponent location,
                            Vector3f position, Chasse chasse, float delta) {
        LocationComponent proie = chasse.proie.getComponent(LocationComponent.class);
        if (proie == null) {
            return;
        }
        Vector3f but = proie.getWorldPosition(new Vector3f());
        float dx = but.x - position.x;
        float dz = but.z - position.z;
        float distance = (float) Math.sqrt(dx * dx + dz * dz);
        if (distance > 1e-4f) {
            chasse.corps = Stride.virer(chasse.corps, (float) Math.atan2(dx, dz),
                    predator.turnRate * delta);
        }

        if (distance > predator.reach) {
            if (!approcher(creature, predator, location, position, chasse, predator.chaseSpeed * delta)) {
                Stride.tourner(creature, location, chasse.corps);
            }
            return;
        }

        Stride.tourner(creature, location, chasse.corps);
        if (chasse.morsure <= 0f) {
            chasse.morsure = predator.cadence;
            chasse.proie.send(new DoDamageEvent(predator.bite, EngineDamageTypes.PHYSICAL.get(),
                    creature, creature));
        }
    }

    /** Tries the heading, then the heading swung aside, and keeps looking at the prey whichever carries. */
    private boolean approcher(EntityRef creature, PredatorComponent predator, LocationComponent location,
                              Vector3f position, Chasse chasse, float pas) {
        for (float ecart : ECARTS) {
            if (Stride.avancer(worldProvider, creature, location, position, chasse.corps + ecart,
                    chasse.corps, pas, predator.stepUp, predator.dropMax)) {
                return true;
            }
        }
        return false;
    }

    /** Puts the chase marker on or takes it off, and only when it actually changes. */
    private void marquer(EntityRef creature, boolean chasse) {
        boolean porte = creature.hasComponent(ChaseComponent.class);
        if (chasse && !porte) {
            creature.addComponent(new ChaseComponent());
        } else if (!chasse && porte) {
            creature.removeComponent(ChaseComponent.class);
        }
    }
}
