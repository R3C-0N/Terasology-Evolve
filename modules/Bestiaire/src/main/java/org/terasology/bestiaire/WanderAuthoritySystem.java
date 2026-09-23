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
 * memory, only a heading and a clock. Two states in fifty lines say what four nodes would say.
 * <p>
 * The hostile half arrived and the tree still did not earn its keep: {@link HuntAuthoritySystem} is the
 * creature that <em>does</em> have a target, and it is a second system rather than a second branch here.
 * Only one of the two moves an animal in a given frame, and {@link ChaseComponent} is which. The legs
 * themselves are shared and belong to neither — they are in {@link Stride}.
 * <p>
 * <strong>The heading and the body are two different angles.</strong> The heading is picked at once and the
 * body swings round to it; without that gap an animal that changes its mind pivots on the spot, which reads
 * as a glitch rather than as a startle. Panic simply turns both dials up — a shorter clock and a faster
 * swing — instead of adding a third state.
 */
@RegisterSystem(RegisterMode.AUTHORITY)
public class WanderAuthoritySystem extends BaseComponentSystem implements UpdateSubscriberSystem {

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
            if (creature.hasComponent(ChaseComponent.class)) {
                // Une bete qui chasse a ses jambes ailleurs (HuntAuthoritySystem). Deux systemes qui
                // deplacent la meme entite dans la meme image ne se partagent pas le mouvement : ils se
                // l'arrachent, et le loup qui charge derive de cote a chaque cap tire au sort.
                humeurs.remove(creature);
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
            humeur.corps = Stride.virer(humeur.corps, humeur.cap,
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
        if (location == null || creature.hasComponent(PredatorComponent.class)) {
            // Un predateur porte les deux temperaments, et le coup ne le fait pas fuir : il le retourne.
            // C'est HuntAuthoritySystem qui recoit le meme evenement et lui designe son frappeur.
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

    /** One stride towards the heading the body has swung to. Returns {@code false} when the way is barred. */
    private boolean avancer(EntityRef creature, LocationComponent location, Vector3f position,
                            Humeur humeur, WanderComponent wander, float pas) {
        return Stride.avancer(worldProvider, creature, location, position, humeur.corps, humeur.corps,
                pas, wander.stepUp, wander.dropMax);
    }

    private void tourner(EntityRef creature, LocationComponent location, Humeur humeur) {
        Stride.tourner(creature, location, humeur.corps);
    }

    private float ecart(float min, float max) {
        return min + hasard.nextFloat() * (max - min);
    }
}
