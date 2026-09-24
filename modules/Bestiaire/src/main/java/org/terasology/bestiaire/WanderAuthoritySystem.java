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
import org.terasology.engine.entitySystem.entity.lifecycleEvents.OnActivatedComponent;
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

    /**
     * Seconds between two looks around. The same four hundredths {@link HuntAuthoritySystem} waits.
     * <p>
     * It throttles nothing expensive any more — {@link Veille} costs one squared distance per character — but
     * it keeps an animal from re-deciding its flight sixty times a second, which reads as a twitch rather
     * than as fright.
     */
    private static final float FLAIR = 0.4f;

    /** How much wider the radius is for calming down again than for taking fright. */
    private static final float CALME = 1.4f;

    /** How far above or below a creature an intruder still counts. Somebody on a roof is not a threat. */
    private static final float HAUTEUR = 6f;

    @In
    private EntityManager entityManager;

    @In
    private WorldProvider worldProvider;

    @In
    private Veille veille;

    private final Map<EntityRef, Humeur> humeurs = new HashMap<>();
    private final Random hasard = new Random();

    /** One animal's state of mind: where it is going, how long for, and how scared it is. */
    private static final class Humeur {
        private float cap;          // the heading it wants, in radians
        private float corps;        // where the body actually points, in radians
        private float minuteur;     // seconds left in the current spell
        private float panique;      // seconds of fright left
        private float blocage;      // seconds before the way may be re-judged
        private float flair;        // seconds before it looks around again
        private float effroi;       // 0 calm, 1 somebody is on top of it
        private float fuite;        // the heading away from whoever frightens it, in radians
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
            if (creature.hasComponent(AccrocheComponent.class)) {
                // Accrochee sous un plafond : elle ne se promene pas, et elle ne pivote meme pas. Le cap
                // et l'horloge n'ont aucun sens pour une bete qui attend, et une bete qui tourne sur elle-
                // meme au plafond se voit de loin — ce qui est exactement ce qu'une embuscade ne doit pas.
                humeurs.remove(creature);
                continue;
            }
            if (ReculAuthoritySystem.enCours(creature)) {
                // Elle est en l'air, et c'est Bullet qui la porte. Marcher pendant ce temps reviendrait a
                // reecrire la position que le moteur physique vient d'ecrire : la bete avancerait par
                // saccades au lieu d'etre projetee.
                humeurs.remove(creature);
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
            Vector3f position = location.getWorldPosition(new Vector3f());
            HomeComponent foyer = creature.getComponent(HomeComponent.class);

            humeur.panique = Math.max(0f, humeur.panique - delta);
            humeur.blocage = Math.max(0f, humeur.blocage - delta);
            humeur.flair -= delta;
            if (humeur.flair <= 0f) {
                humeur.flair = FLAIR;
                sentir(humeur, wander, position);
            }
            humeur.minuteur -= delta;
            if (humeur.minuteur <= 0f) {
                decider(creature, humeur, wander, foyer, position);
            }

            // Un coup vaut l'alarme pleine, une presence vaut ce qu'elle est proche, et l'allure suit
            // l'alarme. A zero, c'est `speed` au bit pres : une bete sans peur ne change pas de conduite.
            float alarme = Math.max(humeur.panique > 0f ? 1f : 0f, humeur.effroi);
            boolean affole = alarme > 0f;
            humeur.corps = Stride.virer(humeur.corps, humeur.cap,
                    (affole ? wander.turnRatePanic : wander.turnRate) * delta);

            boolean bouge = humeur.marche || affole;
            if (bouge && !avancer(creature, location, position, humeur, wander,
                    (wander.speed + (wander.panicSpeed - wander.speed) * alarme) * delta)) {
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
     * A creature is born where it is born, and that is its home until something says otherwise.
     * <p>
     * A prefab holds numbers, not places, so without this a beast set down by a totem would spend its life
     * walking towards coordinate zero. The wild spawner overwrites the point straight afterwards with the
     * group's own; this is what covers everything else.
     */
    @ReceiveEvent(components = LocationComponent.class)
    public void onFoyer(OnActivatedComponent event, EntityRef creature, HomeComponent foyer) {
        if (foyer.pose) {
            return;
        }
        LocationComponent location = creature.getComponent(LocationComponent.class);
        Vector3f position = location.getWorldPosition(new Vector3f());
        if (!position.isFinite()) {
            return;
        }
        foyer.point = position;
        foyer.pose = true;
        creature.saveComponent(foyer);
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

    /**
     * Picks the next spell: stand, stroll, or — while frightened — one more dash.
     * <p>
     * <strong>This is one of the two places a heading is written, and the only one a home may bend.</strong>
     * The other is the blocked branch of {@link #update}, which holds its escape heading for half a clock. A
     * pull that wrote the heading every frame would overwrite that escape on the very next frame, and a herd
     * backed against a cliff would converge on its own centre and grind at the rock for ever.
     */
    private void decider(EntityRef creature, Humeur humeur, WanderComponent wander,
                         HomeComponent foyer, Vector3f position) {
        if (humeur.panique > 0f || humeur.effroi > 0f) {
            humeur.marche = true;
            humeur.minuteur = ecart(wander.dashMin, wander.dashMax);
            float base = humeur.effroi > 0f ? humeur.fuite : humeur.cap;
            humeur.cap = base + ecart(-wander.turnPanic, wander.turnPanic) * (float) Math.PI / 180f;
            return;
        }
        humeur.marche = !humeur.marche;
        if (humeur.marche) {
            humeur.minuteur = ecart(wander.walkMin, wander.walkMax);
            humeur.cap += ecart(-wander.turnCalm, wander.turnCalm) * (float) Math.PI / 180f;
            rentrer(creature, humeur, foyer, position);
        } else {
            humeur.minuteur = ecart(wander.pauseMin, wander.pauseMax);
        }
    }

    /**
     * Notices whoever is near, and how near.
     * <p>
     * The dread is graded and the heading is away: a pheasant pulls off faster the closer one gets, and a
     * deer with a wide radius bolts long before anyone is in reach. Crouching shrinks that radius, which is
     * the one thing that keeps the peaceful half of the bestiary catchable at all — at five and a half to
     * seven and a half blocks a second against a sprint of four and a half, an animal that went straight to
     * its top speed on sight could never be reached again.
     * <p>
     * Taking fright at {@code peur} and calming down at {@code CALME × peur} is not decoration: on a bare
     * threshold an animal would flicker between bolting and grazing at every sweep, one step apart.
     */
    private void sentir(Humeur humeur, WanderComponent wander, Vector3f position) {
        if (wander.peur <= 0f) {
            humeur.effroi = 0f;
            return;
        }
        Veille.Presence plusProche = null;
        float distance = Float.POSITIVE_INFINITY;
        for (Veille.Presence presence : veille.presences()) {
            Vector3f p = presence.position();
            if (Math.abs(p.y - position.y) > HAUTEUR) {
                continue;
            }
            float dx = p.x - position.x;
            float dz = p.z - position.z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d < distance) {
                distance = d;
                plusProche = presence;
            }
        }
        if (plusProche == null) {
            humeur.effroi = 0f;
            return;
        }
        float rayon = wander.peur * (plusProche.discret() ? wander.discretion : 1f);
        float seuil = humeur.effroi > 0f ? rayon * CALME : rayon;
        if (distance > seuil) {
            humeur.effroi = 0f;
            return;
        }
        boolean nouveau = humeur.effroi <= 0f;
        humeur.effroi = Math.max(0f, Math.min(1f, (seuil - distance) / seuil));
        Vector3f loin = new Vector3f(position).sub(plusProche.position());
        if (loin.x * loin.x + loin.z * loin.z > 1e-4f) {
            humeur.fuite = (float) Math.atan2(loin.x, loin.z);
        }
        if (nouveau) {
            humeur.marche = true;
            humeur.minuteur = ecart(wander.dashMin, wander.dashMax);
            humeur.cap = humeur.fuite;
        }
    }

    /**
     * Bends a fresh strolling heading back towards the home point, and lets that point drift after its band.
     * <p>
     * A bend, not a heading: inside {@code franc} it does nothing at all, at {@code laisse} it turns the
     * animal fully home, and in between it leans. That dead middle is what makes a herd a herd rather than a
     * heap — creatures do not collide with one another, and a pull with no slack would stack them up inside
     * each other.
     * <p>
     * Beyond twice the leash the point is simply moved to where the creature stands. One line, and it covers
     * being shoved, falling, being teleported, and somebody building a house over the old spot.
     */
    private void rentrer(EntityRef creature, Humeur humeur, HomeComponent foyer, Vector3f position) {
        if (foyer == null || foyer.tire <= 0f) {
            return;
        }
        if (foyer.suit > 0f && foyer.bande != 0L) {
            Vector3f centre = veille.centre(foyer.bande);
            if (centre != null) {
                foyer.point.lerp(centre, Math.min(1f, foyer.suit));
                creature.saveComponent(foyer);
            }
        }
        float dx = foyer.point.x - position.x;
        float dz = foyer.point.z - position.z;
        float distance = (float) Math.sqrt(dx * dx + dz * dz);
        if (distance > 2f * foyer.laisse) {
            foyer.point.set(position);
            creature.saveComponent(foyer);
            return;
        }
        if (distance < 1e-3f) {
            return;
        }
        float force = Math.max(0f, Math.min(1f,
                (distance - foyer.franc) / Math.max(1e-3f, foyer.laisse - foyer.franc)));
        if (force > 0f) {
            humeur.cap = Stride.virer(humeur.cap, (float) Math.atan2(dx, dz), foyer.tire * force);
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
