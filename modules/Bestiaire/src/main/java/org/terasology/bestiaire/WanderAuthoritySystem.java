// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.terasology.engine.entitySystem.entity.EntityManager;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterMode;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.entitySystem.systems.UpdateSubscriberSystem;
import org.terasology.engine.logic.location.LocationComponent;
import org.terasology.engine.physics.components.shapes.BoxShapeComponent;
import org.terasology.engine.registry.In;
import org.terasology.engine.world.WorldProvider;
import org.terasology.gestalt.entitysystem.event.ReceiveEvent;
import org.terasology.module.health.events.OnDamagedEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * A peaceful animal strolls, and a blow sends it running.
 * <p>
 * There is no behaviour tree here and that is a choice. Terasology has one — {@code Behaviors} — but it buys
 * an editor and a vocabulary for decisions this creature does not make: it has no target, no goal and no
 * memory, only a heading and a clock. Two states in fifty lines say what four nodes would say, and the day a
 * hostile creature needs to chase something is the day the tree earns its keep.
 * <p>
 * The body is kinematic (see {@link GravityAuthoritySystem}), so walking is moving the
 * {@link LocationComponent} and nothing else. Only x and z are touched here; the fall is left to gravity,
 * which runs over the same entities and reads the same ground. The one exception is the step: a creature that
 * walked into a slope and waited for gravity to lift it would spend that frame inside the hill, and the
 * probe would then answer "buried" rather than "climbing".
 * <p>
 * <strong>The heading and the body are two different angles.</strong> The heading is picked at once and the
 * body swings round to it; without that gap an animal that changes its mind pivots on the spot, which reads
 * as a glitch rather than as a startle. Panic simply turns both dials up — a shorter clock and a faster
 * swing — instead of adding a third state.
 */
@RegisterSystem(RegisterMode.AUTHORITY)
public class WanderAuthoritySystem extends BaseComponentSystem implements UpdateSubscriberSystem {

    /** How far above the destination's floor the liquid probe sits. */
    private static final float SONDE_LIQUIDE = 0.25f;

    /** How long a heading picked because the way was barred is kept, in seconds. */
    private static final float DEMI_TOUR = 0.5f;

    @In
    private EntityManager entityManager;

    @In
    private WorldProvider worldProvider;

    private final Map<EntityRef, Humeur> humeurs = new HashMap<>();
    private final Random hasard = new Random();

    /** One animal's state of mind: where it is going, how long for, and how scared it is. */
    private static final class Humeur {
        private float cap;          // the heading it wants, in radians
        private float corps;        // where the body actually points, in radians
        private float minuteur;     // seconds left in the current spell
        private float panique;      // seconds of fright left
        private float blocage;      // seconds before the way may be re-judged
        private boolean marche;
    }

    @Override
    public void update(float delta) {
        humeurs.keySet().removeIf(entity -> !entity.exists());

        for (EntityRef creature : entityManager.getEntitiesWith(WanderComponent.class, LocationComponent.class)) {
            WanderComponent wander = creature.getComponent(WanderComponent.class);
            LocationComponent location = creature.getComponent(LocationComponent.class);
            if (wander == null || location == null) {
                continue;
            }
            Humeur humeur = humeurs.computeIfAbsent(creature, e -> naitre(location));

            humeur.panique = Math.max(0f, humeur.panique - delta);
            humeur.blocage = Math.max(0f, humeur.blocage - delta);
            humeur.minuteur -= delta;
            if (humeur.minuteur <= 0f) {
                decider(humeur, wander);
            }

            boolean affole = humeur.panique > 0f;
            humeur.corps = virer(humeur.corps, humeur.cap,
                    (affole ? wander.turnRatePanic : wander.turnRate) * delta);

            boolean bouge = humeur.marche || affole;
            Vector3f position = location.getWorldPosition(new Vector3f());
            if (bouge && !avancer(creature, location, position, humeur, wander,
                    (affole ? wander.panicSpeed : wander.speed) * delta)) {
                // Mur, ravin ou eau : on se retourne franchement plutot que de pietiner contre. Le cap
                // choisi tient un demi-tour d'horloge : retire a chaque image bloquee, il serait la moyenne
                // de tirages au sort, c'est-a-dire le cap courant, et la bete pietinerait quand meme.
                if (humeur.blocage <= 0f) {
                    humeur.cap = humeur.corps + (float) Math.PI * (0.5f + hasard.nextFloat());
                    humeur.minuteur = Math.min(humeur.minuteur, DEMI_TOUR);
                    humeur.blocage = DEMI_TOUR;
                }
                tourner(creature, location, humeur);
            } else if (!bouge) {
                tourner(creature, location, humeur);
            }
        }
    }

    /**
     * A blow sends it running, and the first stride goes away from whoever struck.
     * <p>
     * After that stride the headings are random again: an animal that fled in a straight line would be easy
     * to follow, and the design asks for something that scatters.
     */
    @ReceiveEvent
    public void onDamaged(OnDamagedEvent event, EntityRef creature, WanderComponent wander) {
        LocationComponent location = creature.getComponent(LocationComponent.class);
        if (location == null) {
            return;
        }
        Humeur humeur = humeurs.computeIfAbsent(creature, e -> naitre(location));
        humeur.panique = wander.panicDuration;
        humeur.marche = true;
        humeur.minuteur = ecart(wander.dashMin, wander.dashMax);

        LocationComponent frappeur = event.getInstigator().getComponent(LocationComponent.class);
        if (frappeur != null) {
            Vector3f fuite = location.getWorldPosition(new Vector3f())
                    .sub(frappeur.getWorldPosition(new Vector3f()));
            if (fuite.x * fuite.x + fuite.z * fuite.z > 1e-4f) {
                humeur.cap = (float) Math.atan2(fuite.x, fuite.z);
                return;
            }
        }
        humeur.cap = humeur.corps + ecart(-(float) Math.PI, (float) Math.PI);
    }

    private Humeur naitre(LocationComponent location) {
        Humeur humeur = new Humeur();
        Vector3f devant = location.getWorldDirection(new Vector3f());
        humeur.cap = (float) Math.atan2(devant.x, devant.z);
        humeur.corps = humeur.cap;
        humeur.minuteur = ecart(0f, 2f);
        return humeur;
    }

    /** Picks the next spell: stand, stroll, or — while frightened — one more dash. */
    private void decider(Humeur humeur, WanderComponent wander) {
        if (humeur.panique > 0f) {
            humeur.marche = true;
            humeur.minuteur = ecart(wander.dashMin, wander.dashMax);
            humeur.cap += ecart(-wander.turnPanic, wander.turnPanic) * (float) Math.PI / 180f;
            return;
        }
        humeur.marche = !humeur.marche;
        if (humeur.marche) {
            humeur.minuteur = ecart(wander.walkMin, wander.walkMax);
            humeur.cap += ecart(-wander.turnCalm, wander.turnCalm) * (float) Math.PI / 180f;
        } else {
            humeur.minuteur = ecart(wander.pauseMin, wander.pauseMax);
        }
    }

    /**
     * One stride. Returns {@code false} when the way is barred and the animal should turn instead.
     * <p>
     * A single probe answers both questions a step asks. {@link Ground#under} climbs out of whatever is solid
     * at the destination, so a floor that comes back far above the feet <em>is</em> the wall, and one that
     * comes back far below is the ledge.
     */
    private boolean avancer(EntityRef creature, LocationComponent location, Vector3f position,
                            Humeur humeur, WanderComponent wander, float pas) {
        BoxShapeComponent box = creature.getComponent(BoxShapeComponent.class);
        float demiHauteur = box == null ? 0.5f : box.extents.y / 2f;
        float pieds = position.y - demiHauteur;

        float x = position.x + (float) Math.sin(humeur.corps) * pas;
        float z = position.z + (float) Math.cos(humeur.corps) * pas;

        float sol = Ground.under(worldProvider, x, pieds, z);
        if (Float.isNaN(sol) || sol - pieds > wander.stepUp || pieds - sol > wander.dropMax) {
            return false;
        }
        if (Ground.liquide(worldProvider, x, sol + SONDE_LIQUIDE, z)) {
            return false;
        }

        position.x = x;
        position.z = z;
        if (sol > pieds) {
            position.y += sol - pieds;
        }
        location.setWorldPosition(position);
        location.setWorldRotation(new Quaternionf().rotationY(humeur.corps));
        creature.saveComponent(location);
        return true;
    }

    private void tourner(EntityRef creature, LocationComponent location, Humeur humeur) {
        location.setWorldRotation(new Quaternionf().rotationY(humeur.corps));
        creature.saveComponent(location);
    }

    /** Moves {@code de} towards {@code vers} by at most {@code max} degrees, the short way round. */
    private static float virer(float de, float vers, float max) {
        float ecart = vers - de;
        float tour = (float) (Math.PI * 2);
        ecart -= tour * Math.floor((ecart + Math.PI) / tour);
        float limite = max * (float) Math.PI / 180f;
        if (ecart > limite) {
            ecart = limite;
        } else if (ecart < -limite) {
            ecart = -limite;
        }
        return de + ecart;
    }

    private float ecart(float min, float max) {
        return min + hasard.nextFloat() * (max - min);
    }
}
