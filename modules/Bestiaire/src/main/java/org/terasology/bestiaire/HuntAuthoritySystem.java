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
import org.terasology.engine.logic.health.EngineDamageTypes;
import org.terasology.engine.logic.location.LocationComponent;
import org.terasology.engine.registry.In;
import org.terasology.engine.world.WorldProvider;
import org.terasology.gestalt.entitysystem.event.ReceiveEvent;
import org.terasology.module.health.events.DoDamageEvent;
import org.terasology.module.health.events.OnDamagedEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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

    /** Above this, in blocks per second, a prey is running and everybody charges instead of circling. */
    private static final float PROIE_LENTE = 1.5f;

    /** Seconds a station is held before the whole pack closes in, whatever the geometry says. */
    private static final float POSTE = 3f;

    /** How many points are sampled on a ring when a night hunter goes looking for shade at daybreak. */
    private static final int SONDES = 12;

    /** Seconds a blind creature keeps going towards a noise after the noise has stopped. */
    private static final float SOURDINE = 6f;

    /** How close to that spot counts as having arrived, in blocks, and given up. */
    private static final float TATONNE = 1.5f;

    /** How far below a clinging creature a prey must be before the ambush is worth springing, in blocks. */
    private static final float CHUTE = 1f;

    private static final float TAU = (float) (Math.PI * 2);

    @In
    private EntityManager entityManager;

    @In
    private WorldProvider worldProvider;

    @In
    private Veille veille;

    private final Map<EntityRef, Chasse> chasses = new HashMap<>();
    private final Random hasard = new Random();

    /** One hunter's state: whom it is after, where it is looking, and its two clocks. */
    private static final class Chasse {
        private EntityRef proie = EntityRef.NULL;
        private float corps;            // where the body points, in radians
        private float flair;            // seconds before the surroundings are swept again
        private float morsure;          // seconds before the next bite
        private int rang = -1;          // its station on the ring, or -1 for "charge"
        private int taille = 1;         // how many are on the prey
        private float base;             // where the pack stands, as seen from the prey, in radians
        private float poste;            // seconds of station left before closing in
        private float allure;           // how fast the prey is going, in blocks per second
        private Vector3f ouEtaitLaProie; // where it was at the last sniff
        private boolean terree;         // whether it has already found its shade today
        private boolean reveille;       // whether a blow has taken its conditions away
        private Vector3f echo;          // where a noise came from, for whatever cannot see
        private float sourdine;         // seconds of walking towards that noise still owed
    }

    @Override
    public void update(float delta) {
        chasses.keySet().removeIf(entity -> !entity.exists());

        // Une seule liste par image, et les meutes se calculent dessus : `getEntitiesWith` parcourt tout le
        // magasin d'entites, donc la demander une fois par predateur rendrait le cout quadratique en monde.
        List<EntityRef> betes = new ArrayList<>();
        entityManager.getEntitiesWith(PredatorComponent.class, LocationComponent.class).forEach(betes::add);

        for (EntityRef creature : betes) {
            PredatorComponent predator = creature.getComponent(PredatorComponent.class);
            LocationComponent location = creature.getComponent(LocationComponent.class);
            if (predator == null || location == null) {
                continue;
            }
            Chasse chasse = chasses.computeIfAbsent(creature, e -> naitre(location));
            chasse.flair -= delta;
            chasse.morsure -= delta;
            chasse.sourdine -= delta;

            Vector3f position = location.getWorldPosition(new Vector3f());
            if (chasse.flair <= 0f) {
                chasse.flair = FLAIR;
                chasse.proie = juger(creature, chasse, position, predator);
                if (chasse.proie.exists()) {
                    jauger(chasse);
                    meute(creature, predator, chasse, position, betes);
                } else {
                    terrer(creature, position, predator, chasse);
                }
            }
            chasse.poste -= delta;
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
        // Un coup leve toutes les conditions. Le mille-pattes cesse de se figer sous le regard et le lezard
        // lache prise sans attendre qu'on passe dessous : c'est le « sans conditions apres un coup » du
        // cahier des charges, et c'est aussi la seule facon de rendre les deux combattables.
        chasse.reveille = true;
        chasse.sourdine = SOURDINE;
        chasse.echo = null;
        if (creature.hasComponent(AccrocheComponent.class)) {
            creature.removeComponent(AccrocheComponent.class);
        }
        marquer(creature, true);
    }

    private Chasse naitre(LocationComponent location) {
        Chasse chasse = new Chasse();
        Vector3f devant = location.getWorldDirection(new Vector3f());
        chasse.corps = (float) Math.atan2(devant.x, devant.z);
        // La premiere echeance est tiree au sort, et ce n'est pas une coquetterie : nee a zero, elle ferait
        // renifler ensemble, a jamais, tous les predateurs crees dans la meme image. Une meute qui apparait
        // produirait un a-coup metronomique toutes les 0,4 s au lieu d'un travail etale.
        chasse.flair = hasard.nextFloat() * FLAIR;
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
    private EntityRef juger(EntityRef creature, Chasse chasse, Vector3f position,
                           PredatorComponent predator) {
        List<Veille.Presence> presences = veille.presences();

        // Les deux perceptions des Profondeurs passent avant tout le reste, parce qu'elles remplacent la
        // vue et non parce qu'elles s'y ajoutent : accrochee, la bete ne voit que ce qui passe dessous ;
        // aveugle, elle n'entend que le bruit. Une bete de la surface n'entre dans aucune des deux.
        if (creature.hasComponent(AccrocheComponent.class)) {
            return guetter(creature, presences, position, predator);
        }
        if (predator.ouie > 0f) {
            return ecouter(chasse, presences, position, predator);
        }

        HomeComponent foyer = creature.getComponent(HomeComponent.class);
        boolean chezSoi = predator.portee <= 0f || foyer == null
                || plat(position, foyer.point) <= predator.portee;
        // Sous le regard, rien ne se garde : une bete qui se fige quand on la fixe doit se figer aussi en
        // pleine course, sans quoi elle ne se fige qu'a l'arret et le joueur n'a rien appris.
        boolean surveillee = predator.guet > 0f && !chasse.reveille;
        if (!surveillee) {
            for (Veille.Presence presence : presences) {
                if (presence.personnage().equals(chasse.proie)
                        && portee(presence.position(), position, predator) <= predator.giveUp
                        && chezSoi) {
                    return chasse.proie;
                }
            }
        }
        // Ni la bete qui attend un coup ni celle qui est hors de ses heures ne balayent leurs alentours :
        // elles n'ont de proie que celle que `onDamaged` leur donne. Elles cessent de chasser, elles ne
        // cessent pas de se defendre, et c'est la bonne asymetrie.
        if (predator.onlyWhenStruck || !aLHeure(creature)) {
            return EntityRef.NULL;
        }
        EntityRef trouvee = EntityRef.NULL;
        float plusProche = predator.sight;
        for (Veille.Presence presence : presences) {
            if (predator.garde > 0f && foyer != null
                    && plat(presence.position(), foyer.point) > predator.garde) {
                continue;
            }
            if (surveillee && regarde(presence, position, predator.guet)) {
                continue;
            }
            float distance = portee(presence.position(), position, predator);
            if (distance <= plusProche) {
                plusProche = distance;
                trouvee = presence.personnage();
            }
        }
        return trouvee;
    }

    /**
     * Whether this character is looking at the point where the creature stands.
     * <p>
     * A dot product against the gaze, which carries the pitch — in a gallery one looks down far more often
     * than ahead, and a test on the body's yaw alone would call that "not looking" and let the centipede walk
     * up to somebody staring straight at it.
     */
    private boolean regarde(Veille.Presence presence, Vector3f position, float cone) {
        Vector3f vers = new Vector3f(position).sub(presence.position());
        if (vers.lengthSquared() < 1e-6f) {
            return true;
        }
        Vector3f oeil = new Vector3f(presence.regard());
        if (oeil.lengthSquared() < 1e-6f) {
            return false;
        }
        return oeil.normalize().dot(vers.normalize()) >= (float) Math.cos(Math.toRadians(cone / 2f));
    }

    /**
     * Hanging from a ceiling, waiting for somebody to walk underneath.
     * <p>
     * The whole ambush is the component coming off: {@link GravityAuthoritySystem} has been ready the entire
     * time, and what falls on the prey is the creature's own weight from whatever height the roof was. There
     * is no lunge to write, no arc to tune, and nothing to replicate — the client already receives the
     * positions.
     * <p>
     * It springs on <em>underneath</em> and not on <em>near</em>: the horizontal radius is small and the prey
     * must be a clear block lower. Walking past a lizard leaves it hanging there, which is the point of
     * putting it on the ceiling in the first place.
     */
    private EntityRef guetter(EntityRef creature, List<Veille.Presence> presences, Vector3f position,
                              PredatorComponent predator) {
        if (predator.embuscade <= 0f) {
            return EntityRef.NULL;
        }
        for (Veille.Presence presence : presences) {
            Vector3f q = presence.position();
            if (position.y - q.y < CHUTE || plat(q, position) > predator.embuscade) {
                continue;
            }
            creature.removeComponent(AccrocheComponent.class);
            return presence.personnage();
        }
        return EntityRef.NULL;
    }

    /**
     * Blind, and therefore hunting a place rather than a person.
     * <p>
     * Two radii have to agree for a character to be heard: the creature's ear and the character's own noise,
     * which is a walk, a run or a blow — and nothing at all while crouching. What is remembered is
     * <strong>where the noise was</strong>, not who made it, and that is the whole difference between this
     * and every other hunter in the module: go silent and step aside and it arrives at the wrong place, feels
     * about, and gives up.
     * <p>
     * The prey reference is still carried, because a bite has to land on somebody; it is the aim that is
     * frozen. Silence buys {@link #SOURDINE} seconds, and arriving at the spot ends it early.
     */
    private EntityRef ecouter(Chasse chasse, List<Veille.Presence> presences, Vector3f position,
                              PredatorComponent predator) {
        Veille.Presence entendue = null;
        float plusProche = Float.POSITIVE_INFINITY;
        for (Veille.Presence presence : presences) {
            float distance = portee(presence.position(), position, predator);
            if (distance > predator.ouie || distance > presence.bruit() || distance >= plusProche) {
                continue;
            }
            plusProche = distance;
            entendue = presence;
        }
        if (entendue != null) {
            chasse.echo = new Vector3f(entendue.position());
            chasse.sourdine = SOURDINE;
            return entendue.personnage();
        }
        if (chasse.sourdine <= 0f || chasse.echo == null || !chasse.proie.exists()
                || plat(position, chasse.echo) <= TATONNE) {
            chasse.echo = null;
            return EntityRef.NULL;
        }
        return chasse.proie;
    }

    /** Whether a creature is within the hours its habitat gives it. No habitat means every hour. */
    private boolean aLHeure(EntityRef creature) {
        HabitatComponent habitat = creature.getComponent(HabitatComponent.class);
        return habitat == null || habitat.aLHeure(worldProvider.getTime().getDays() % 1f);
    }

    /** How fast the prey is going, from where it was one sniff ago. */
    private void jauger(Chasse chasse) {
        LocationComponent proie = chasse.proie.getComponent(LocationComponent.class);
        if (proie == null) {
            return;
        }
        Vector3f ou = proie.getWorldPosition(new Vector3f());
        if (chasse.ouEtaitLaProie != null) {
            chasse.allure = plat(ou, chasse.ouEtaitLaProie) / FLAIR;
        }
        chasse.ouEtaitLaProie = ou;
    }

    /**
     * Who else is on this prey, and which station each of them takes.
     * <p>
     * A packmate with no prey adopts this one — that is the sharing, and it is one line. The stations are
     * handed out by entity id, so they are the same on every beast in the pack without anyone agreeing on
     * anything: no negotiation, no thrashing when two of them would have picked the same slot, nothing to
     * persist.
     * <p>
     * <strong>The nearest never takes one.</strong> That is the damage guarantee: however prettily the others
     * fan out, one of them is always closing.
     */
    private void meute(EntityRef creature, PredatorComponent predator, Chasse chasse, Vector3f position,
                       List<EntityRef> betes) {
        chasse.rang = -1;
        chasse.taille = 1;
        if (predator.meute <= 0f) {
            return;
        }
        CreatureComponent espece = creature.getComponent(CreatureComponent.class);
        LocationComponent locProie = chasse.proie.getComponent(LocationComponent.class);
        if (espece == null || espece.species == null || locProie == null) {
            return;
        }
        Vector3f but = locProie.getWorldPosition(new Vector3f());
        List<EntityRef> ensemble = new ArrayList<>();
        Vector3f centre = new Vector3f();
        for (EntityRef autre : betes) {
            CreatureComponent voisine = autre.getComponent(CreatureComponent.class);
            LocationComponent ou = autre.getComponent(LocationComponent.class);
            Chasse sienne = chasses.get(autre);
            if (voisine == null || ou == null || sienne == null
                    || !espece.species.equals(voisine.species)) {
                continue;
            }
            Vector3f p = ou.getWorldPosition(new Vector3f());
            if (plat(p, position) > predator.meute) {
                continue;
            }
            if (!autre.equals(creature) && !sienne.proie.exists()) {
                sienne.proie = chasse.proie;
                marquer(autre, true);
            }
            if (!sienne.proie.equals(chasse.proie)) {
                continue;
            }
            ensemble.add(autre);
            centre.add(p);
        }
        if (ensemble.size() < 2) {
            return;
        }
        centre.div(ensemble.size());
        chasse.base = (float) Math.atan2(centre.x - but.x, centre.z - but.z);
        chasse.taille = ensemble.size();

        EntityRef plusProche = null;
        float meilleure = Float.POSITIVE_INFINITY;
        int rang = 0;
        for (EntityRef autre : ensemble) {
            Vector3f p = autre.getComponent(LocationComponent.class).getWorldPosition(new Vector3f());
            float d = plat(p, but);
            if (d < meilleure) {
                meilleure = d;
                plusProche = autre;
            }
            if (autre.getId() < creature.getId()) {
                rang++;
            }
        }
        if (creature.equals(plusProche)) {
            chasse.rang = -1;
        } else {
            if (chasse.rang < 0) {
                chasse.poste = POSTE;
            }
            chasse.rang = rang;
        }
    }

    /**
     * Daylight, no prey: the night hunter goes and lies down in the shade.
     * <p>
     * Not a search and not a mechanism — the home point is simply written once, to the darkest cell on a ring
     * around the beast, and {@link WanderAuthoritySystem} walks it there as it walks anything home. Sky light
     * is propagated and takes no account of the hour, so a low reading means cover: a canopy, an overhang, a
     * cave mouth.
     * <p>
     * <strong>Every sample is gated on the chunk being loaded</strong>, because an absent chunk reads zero —
     * the darkest place in the world is always just outside it, and without the gate every nocturnal creature
     * alive would walk towards the edge of the loaded region at dawn, together.
     * <p>
     * <strong>When it finds nothing dark enough it beds down where it stands.</strong> That is not a
     * fallback, it is the ordinary case: on plains and in snow the sky light is fifteen everywhere and there
     * is no gradient to follow at all.
     */
    private void terrer(EntityRef creature, Vector3f position, PredatorComponent predator, Chasse chasse) {
        HomeComponent foyer = creature.getComponent(HomeComponent.class);
        HabitatComponent habitat = creature.getComponent(HabitatComponent.class);
        if (foyer == null || habitat == null || foyer.tire <= 0f) {
            return;
        }
        if (aLHeure(creature)) {
            chasse.terree = false;
            return;
        }
        if (chasse.terree) {
            return;
        }
        chasse.terree = true;
        Vector3f meilleur = new Vector3f(position);
        int plusSombre = Integer.MAX_VALUE;
        for (int i = 0; i < SONDES; i++) {
            float a = i * TAU / SONDES;
            float x = position.x + (float) Math.sin(a) * predator.sight;
            float z = position.z + (float) Math.cos(a) * predator.sight;
            float sol = Ground.under(worldProvider, x, position.y, z);
            if (Float.isNaN(sol)) {
                continue;
            }
            int lumiere = worldProvider.getSunlight(new Vector3f(x, sol + 1f, z));
            if (lumiere < plusSombre) {
                plusSombre = lumiere;
                meilleur.set(x, sol, z);
            }
        }
        foyer.point = meilleur;
        creature.saveComponent(foyer);
    }

    /** Horizontal distance, the only one that matters to something that walks. */
    private static float plat(Vector3f a, Vector3f b) {
        float dx = a.x - b.x;
        float dz = a.z - b.z;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Horizontal distance, or infinity when the candidate is too far above or below to be worth a thought.
     * <p>
     * Horizontal on purpose: a creature stands on the ground and so does what it hunts, and measuring the
     * slant would make it give up on someone standing on a hillock. The vertical limit is a separate, coarser
     * question — a prey on a roof is not a prey.
     */
    private float portee(Vector3f but, Vector3f position, PredatorComponent predator) {
        if (Math.abs(but.y - position.y) > predator.reachUp) {
            return Float.POSITIVE_INFINITY;
        }
        float dx = but.x - position.x;
        float dz = but.z - position.z;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Whether this is a living character that has not been excused from being hunted.
     * <p>
     * The whole test is now "is it in {@link Veille#presences()}", because the sweep already filtered on
     * alive, located and unconsumed — and asked {@link BeforeHuntedEvent} once for everybody instead of once
     * per creature.
     */
    private boolean chassable(EntityRef cible) {
        return veille.remarquable(cible);
    }

    /** One frame of a chase: turn towards the prey, close the gap, and bite once in reach. */
    private void poursuivre(EntityRef creature, PredatorComponent predator, LocationComponent location,
                            Vector3f position, Chasse chasse, float delta) {
        LocationComponent proie = chasse.proie.getComponent(LocationComponent.class);
        if (proie == null) {
            return;
        }
        Vector3f but = proie.getWorldPosition(new Vector3f());
        // Ce qui decide la morsure est la distance a la proie ; ce qui decide les jambes est la distance au
        // but. Les deux ne sont la meme chose que pour une bete qui voit : l'aveugle court vers un lieu, et
        // mordre parce qu'il y est arrive planterait ses crocs dans l'air a cote de quelqu'un.
        float ecart = plat(but, position);
        if (predator.ouie > 0f && chasse.echo != null) {
            but = chasse.echo;
        }
        float distance = plat(but, position);

        // Le poste : on court se placer sur le cercle tant qu'on n'y est pas, puis on plonge. Trois verrous
        // le rendent inoffensif — la proie doit avoir ralenti, le plus proche ne le prend jamais, et il
        // expire. Sans eux une meute qui encercle ne mord personne.
        Vector3f vise = but;
        if (predator.cercle > 0f && chasse.rang >= 0 && chasse.taille > 1
                && chasse.poste > 0f && chasse.allure <= PROIE_LENTE
                && distance > predator.reach + 1.5f) {
            float angle = chasse.base + TAU * chasse.rang / chasse.taille;
            vise = new Vector3f(but.x + (float) Math.sin(angle) * predator.cercle, but.y,
                    but.z + (float) Math.cos(angle) * predator.cercle);
        }

        float dx = vise.x - position.x;
        float dz = vise.z - position.z;
        if (dx * dx + dz * dz > 1e-8f) {
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
        if (ecart <= predator.reach && chasse.morsure <= 0f) {
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
