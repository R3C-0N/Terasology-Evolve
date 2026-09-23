// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.joml.Vector3f;
import org.joml.Vector3ic;
import org.terasology.biomesAPI.Biome;
import org.terasology.biomesAPI.BiomeRegistry;
import org.terasology.engine.config.Config;
import org.terasology.engine.core.Time;
import org.terasology.engine.entitySystem.entity.EntityManager;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.prefab.Prefab;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterMode;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.entitySystem.systems.UpdateSubscriberSystem;
import org.terasology.engine.logic.location.LocationComponent;
import org.terasology.engine.registry.In;
import org.terasology.engine.registry.Share;
import org.terasology.engine.world.WorldComponent;
import org.terasology.engine.world.WorldProvider;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.block.BlockManager;
import org.terasology.engine.world.chunks.Chunks;
import org.terasology.engine.world.chunks.event.BeforeChunkUnload;
import org.terasology.gestalt.entitysystem.event.ReceiveEvent;
import org.terasology.gestalt.naming.Name;
import org.terasology.module.health.events.OnDamagedEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * The world lays down its own wildlife, and picks it up again.
 * <p>
 * <strong>Picking it up is the half that decides whether the feature is shippable</strong>, so it is written
 * first and it runs first. Terasology serialises a creature into its chunk when the chunk unloads and restores
 * it verbatim when the chunk returns; nothing expires, nothing is capped, and {@code setPersistent(false)}
 * does not help because that test only looks at owned entities. A spawner without a sweep would therefore add
 * animals to the save for as long as the save exists — walk back through last week's forest and the whole
 * backlog wakes up in one frame.
 * <p>
 * Three rules answer it, and the third is the guarantee: wild creatures beyond {@code OUBLI} are destroyed; a
 * creature that was just struck is spared for a minute so it cannot vanish mid-chase; and whatever both miss
 * is destroyed on {@code BeforeChunkUnload}, <em>before</em> the chunk is written. Nothing wild reaches the
 * disk.
 * <p>
 * <strong>The radii are derived from the view distance, never written down.</strong> Only the chunks around a
 * player are loaded, so a spot beyond them cannot be probed and a creature beyond them cannot be swept. The
 * relation to keep is {@code anneauMax < OUBLI < rayon chargé}: invert the first and creatures blink in and
 * out at the edge of the ring, invert the second and they leak past the sweep and start accumulating.
 */
@RegisterSystem(RegisterMode.AUTHORITY)
@Share(Faune.class)
public class FauneAuthoritySystem extends BaseComponentSystem implements UpdateSubscriberSystem, Faune {

    /** Seconds between two attempts, and how many spots each attempt tries before giving up. */
    private static final float PAS = 5f;
    private static final int ESSAIS = 3;

    /** The ring, the forgetting distance and the crowd radius, as fractions of the guaranteed loaded radius. */
    private static final float ANNEAU_PRES = 0.38f;
    private static final float ANNEAU_LOIN = 0.69f;
    private static final float OUBLI = 0.87f;
    private static final float FOULE = 1.0f;

    /**
     * How far above or below the player a spot may be.
     * <p>
     * Not a taste question: {@link Ground#under} climbs eight blocks and descends sixty-five <em>from where
     * the probe starts</em>, and the probe starts at the player. A column much higher comes back as a wrong
     * answer inside the rock; a column much lower comes back in mid-air and the creature free-falls. Rejecting
     * the slope also stops a herd appearing on a clifftop nobody can see.
     */
    private static final float DENIVELE = 24f;

    /** How many wild creatures the world holds at once, all species and all players together. */
    private static final int PLAFOND = 60;

    /** Seconds a struck creature is spared by the forgetting sweep. */
    private static final long GRACE = 60_000L;

    /** How many verdicts the journal keeps, which is what a player reads when a species never shows up. */
    private static final int MEMOIRE = 12;

    /**
     * Ground a wild creature will stand on.
     * <p>
     * The engine records no provenance for a block — there is no way to ask who put it there — so this list
     * plus open sky is the whole defence against a herd of cows appearing on somebody's floor.
     */
    private static final String[] SOLS = {
        "CoreAssets:Grass", "CoreAssets:Snow", "CoreAssets:Sand", "CoreAssets:Dirt", "CoreAssets:Gravel",
    };

    private static final float TAU = (float) (Math.PI * 2);

    @In
    private EntityManager entityManager;

    @In
    private WorldProvider worldProvider;

    @In
    private BiomeRegistry biomeRegistry;

    @In
    private BlockManager blockManager;

    @In
    private Bestiary bestiary;

    @In
    private Veille veille;

    @In
    private Time time;

    @In
    private Config config;

    private final Random hasard = new Random();
    private final Deque<String> journal = new ArrayDeque<>();
    private final Set<Block> sols = new HashSet<>();
    private float horloge = PAS;
    private float rayon = 64f;

    @Override
    public void initialise() {
        // Un quart de la region chargee de chaque cote : les chunks vont de c-d/2 a c+d/2, donc la garantie
        // depuis le joueur est la moitie de la distance en chunks, fois la largeur d'un chunk.
        Vector3ic chunks = config.getRendering().getViewDistance().getChunkDistance();
        rayon = Math.max(32f, (chunks.x() / 2) * (float) Chunks.SIZE_X);
        for (String uri : SOLS) {
            sols.add(blockManager.getBlock(uri));
        }
    }

    @Override
    public void update(float delta) {
        horloge -= delta;
        if (horloge > 0f) {
            return;
        }
        horloge = PAS;
        if (veille.joueurs().isEmpty()) {
            // Personne au monde : ni naissance ni oubli. Balayer ici viderait la campagne pendant le
            // chargement, avant meme que le personnage n'existe.
            return;
        }
        oublier();
        peupler(ESSAIS);
    }

    // --- ce que le monde reprend --------------------------------------------------------------------

    /** Destroys the wild creatures no player is near any more. */
    private void oublier() {
        List<Vector3f> joueurs = veille.joueurs();
        long maintenant = time.getGameTimeInMs();
        float limite = OUBLI * rayon;
        List<EntityRef> partantes = new ArrayList<>();
        for (EntityRef creature : entityManager.getEntitiesWith(SauvageComponent.class,
                LocationComponent.class)) {
            SauvageComponent sauvage = creature.getComponent(SauvageComponent.class);
            LocationComponent location = creature.getComponent(LocationComponent.class);
            if (sauvage == null || location == null) {
                continue;
            }
            if (creature.hasComponent(ChaseComponent.class) || maintenant < sauvage.protegeJusqua) {
                continue;
            }
            Vector3f position = location.getWorldPosition(new Vector3f());
            if (!position.isFinite() || distance(position, joueurs) > limite) {
                partantes.add(creature);
            }
        }
        // Detruire apres la boucle : l'iterable de `getEntitiesWith` court sur le magasin d'entites lui-meme.
        partantes.forEach(EntityRef::destroy);
    }

    /**
     * The guarantee: nothing wild is written to disk.
     * <p>
     * With the forgetting radius inside the loaded region this should never find anything. It exists for the
     * case where it does — a teleport, a view distance changed mid-game, a crash that saved a creature that
     * rule one had not reached yet.
     */
    @ReceiveEvent(components = WorldComponent.class)
    public void onDechargement(BeforeChunkUnload event, EntityRef monde) {
        Vector3ic chunk = event.getChunkPos();
        float x0 = chunk.x() * (float) Chunks.SIZE_X;
        float y0 = chunk.y() * (float) Chunks.SIZE_Y;
        float z0 = chunk.z() * (float) Chunks.SIZE_Z;
        List<EntityRef> partantes = new ArrayList<>();
        for (EntityRef creature : entityManager.getEntitiesWith(SauvageComponent.class,
                LocationComponent.class)) {
            LocationComponent location = creature.getComponent(LocationComponent.class);
            if (location == null) {
                continue;
            }
            Vector3f p = location.getWorldPosition(new Vector3f());
            if (p.isFinite()
                    && p.x >= x0 && p.x < x0 + Chunks.SIZE_X
                    && p.y >= y0 && p.y < y0 + Chunks.SIZE_Y
                    && p.z >= z0 && p.z < z0 + Chunks.SIZE_Z) {
                partantes.add(creature);
            }
        }
        partantes.forEach(EntityRef::destroy);
    }

    /** A blow spares the creature from the sweep for a while, so nothing vanishes mid-chase. */
    @ReceiveEvent
    public void onFrappe(OnDamagedEvent event, EntityRef creature, SauvageComponent sauvage) {
        sauvage.protegeJusqua = time.getGameTimeInMs() + GRACE;
        creature.saveComponent(sauvage);
    }

    // --- ce que le monde pose -----------------------------------------------------------------------

    @Override
    public int peupler(int essais) {
        List<Vector3f> joueurs = veille.joueurs();
        if (joueurs.isEmpty()) {
            return 0;
        }
        int nes = 0;
        for (int i = 0; i < essais; i++) {
            nes += tenter(joueurs.get(hasard.nextInt(joueurs.size())));
        }
        return nes;
    }

    /** One spot, tried from the cheapest test to the dearest, abandoned at the first refusal. */
    private int tenter(Vector3f joueur) {
        if (total() >= PLAFOND) {
            return noter("plafond du monde");
        }
        float angle = hasard.nextFloat() * TAU;
        float loin = (ANNEAU_PRES + hasard.nextFloat() * (ANNEAU_LOIN - ANNEAU_PRES)) * rayon;
        float x = joueur.x + (float) Math.sin(angle) * loin;
        float z = joueur.z + (float) Math.cos(angle) * loin;

        float sol = Ground.under(worldProvider, x, joueur.y, z);
        if (Float.isNaN(sol)) {
            return noter("hors chunk");
        }
        if (Math.abs(sol - joueur.y) > DENIVELE) {
            return noter("denivele");
        }
        if (Ground.liquide(worldProvider, x, sol + 0.25f, z)) {
            return noter("liquide");
        }
        if (!solNaturel(x, sol, z)) {
            return noter("sol");
        }
        Optional<Biome> biome = biomeRegistry.getBiome((int) Math.floor(x),
                (int) Math.floor(sol - 0.5f), (int) Math.floor(z));
        if (biome.isEmpty()) {
            return noter("sans biome");
        }
        Name ici = biome.get().getId();

        float heure = worldProvider.getTime().getDays() % 1f;
        Prefab prefab = tirer(ici, heure);
        if (prefab == null) {
            return noter("biome=" + ici);
        }
        HabitatComponent habitat = prefab.getComponent(HabitatComponent.class);
        String espece = prefab.getComponent(CreatureComponent.class).species;

        if (!cielOuvert(habitat, x, sol, z)) {
            return noter(espece + " : ciel");
        }
        if (!terrain(habitat, x, sol, z)) {
            return noter(espece + " : terrain");
        }
        if (recensement(new Vector3f(x, sol, z), FOULE * rayon).getOrDefault(espece, 0) >= habitat.plafond) {
            return noter(espece + " : deja " + habitat.plafond);
        }
        int nes = poser(prefab, habitat, x, sol, z);
        noter(espece + " x" + nes);
        return nes;
    }

    /** Whether the ground is one a wild creature stands on, and flat enough that nobody built it. */
    private boolean solNaturel(float x, float sol, float z) {
        int y = (int) Math.floor(sol - 0.5f);
        if (!sols.contains(worldProvider.getBlock((int) Math.floor(x), y, (int) Math.floor(z)))) {
            return false;
        }
        // Deux voisines sur huit ont le droit de decrocher : une dune naturelle n'est pas une table, et
        // exiger les huit refusait des pentes parfaitement praticables. Ce n'est pas la planeite qui protege
        // une construction — un plancher est plat —, c'est la matiere, testee juste au-dessus.
        int ecarts = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                float voisin = Ground.under(worldProvider, x + dx, sol, z + dz);
                if (Float.isNaN(voisin) || Math.abs(voisin - sol) > 1.01f) {
                    ecarts++;
                }
            }
        }
        return ecarts <= 2;
    }

    /** Draws a species among those whose habitat fits this biome and this hour, weighted by its density. */
    private Prefab tirer(Name biome, float heure) {
        List<Prefab> candidats = new ArrayList<>();
        float somme = 0f;
        for (Prefab prefab : bestiary.sauvages()) {
            HabitatComponent habitat = prefab.getComponent(HabitatComponent.class);
            CreatureComponent creature = prefab.getComponent(CreatureComponent.class);
            if (habitat == null || creature == null || !habitat.aLHeure(heure)) {
                continue;
            }
            for (String nom : habitat.biomes) {
                if (new Name(nom).equals(biome)) {
                    candidats.add(prefab);
                    somme += Math.max(0f, habitat.densite);
                    break;
                }
            }
        }
        if (candidats.isEmpty() || somme <= 0f) {
            return null;
        }
        float tirage = hasard.nextFloat() * somme;
        for (Prefab prefab : candidats) {
            tirage -= Math.max(0f, prefab.getComponent(HabitatComponent.class).densite);
            if (tirage <= 0f) {
                return prefab;
            }
        }
        return candidats.get(candidats.size() - 1);
    }

    /**
     * The terrain a species asks for, beyond its biome.
     * <p>
     * {@code exige} says how many of the enabled tests must pass, which is how "the water's edge <em>or</em> a
     * dark hollow" is said without a branch: the bear enables two and requires one.
     */
    private boolean terrain(HabitatComponent habitat, float x, float sol, float z) {
        int allumes = 0;
        int passes = 0;

        if (habitat.ombre < 16) {
            allumes++;
            if (worldProvider.getSunlight(new Vector3f(x, sol + 1f, z)) <= habitat.ombre) {
                passes++;
            }
        }

        if (!habitat.couvert.isEmpty()) {
            allumes++;
            Block dessus = worldProvider.getBlock((int) Math.floor(x), (int) Math.floor(sol + 0.5f),
                    (int) Math.floor(z));
            for (String uri : habitat.couvert) {
                if (blockManager.getBlock(uri).equals(dessus)) {
                    passes++;
                    break;
                }
            }
        }

        if (habitat.eau > 0f) {
            allumes++;
            if (eauProche(x, sol, z, habitat.eau)) {
                passes++;
            }
        }

        if (allumes == 0) {
            return true;
        }
        return passes >= (habitat.exige > 0 ? Math.min(habitat.exige, allumes) : allumes);
    }

    /**
     * Open sky, for everyone who has not asked for shade instead.
     * <p>
     * This one test is what forbids appearing under a roof, in a corridor or inside a walled base — and it
     * costs one lookup, where asking who laid the blocks is impossible: the engine records no provenance at
     * all. Sky light is propagated and time-independent, so an open field reads fifteen at midnight too.
     */
    private boolean cielOuvert(HabitatComponent habitat, float x, float sol, float z) {
        return habitat.ombre < 16
                || worldProvider.getSunlight(new Vector3f(x, sol + 1f, z)) >= habitat.ciel;
    }

    /** Whether a liquid cell sits within {@code portee}, sampled on two rings rather than swept. */
    private boolean eauProche(float x, float sol, float z, float portee) {
        for (float r : new float[] {portee * 0.5f, portee}) {
            for (int i = 0; i < 8; i++) {
                float a = i * TAU / 8f;
                float px = x + (float) Math.sin(a) * r;
                float pz = z + (float) Math.cos(a) * r;
                float psol = Ground.under(worldProvider, px, sol, pz);
                if (!Float.isNaN(psol) && Ground.liquide(worldProvider, px, psol + 0.25f, pz)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Lays the group down, re-probing under every member.
     * <p>
     * A member offset sideways from the leader's height ends up inside the slope, and {@link Ground} then
     * lifts it out by a block the moment it is looked at — a visible pop. A group of five that places three is
     * a group of three, not a failure.
     */
    private int poser(Prefab prefab, HabitatComponent habitat, float x, float sol, float z) {
        int taille = habitat.groupeMin
                + (habitat.groupeMax > habitat.groupeMin
                        ? hasard.nextInt(habitat.groupeMax - habitat.groupeMin + 1) : 0);
        long bande = hasard.nextLong() | 1L;
        Prefab autre = habitat.melange.isEmpty() ? null
                : bestiary.creature(habitat.melange).orElse(null);
        int nes = 0;
        for (int i = 0; i < taille; i++) {
            float mx = x;
            float mz = z;
            if (i > 0) {
                float a = hasard.nextFloat() * TAU;
                float d = habitat.ecart * (0.35f + hasard.nextFloat() * 0.65f);
                mx += (float) Math.sin(a) * d;
                mz += (float) Math.cos(a) * d;
            }
            float msol = Ground.under(worldProvider, mx, sol, mz);
            if (Float.isNaN(msol) || Math.abs(msol - sol) > 2f
                    || Ground.liquide(worldProvider, mx, msol + 0.25f, mz)) {
                continue;
            }
            Prefab quoi = (autre != null && hasard.nextFloat() < habitat.chanceMelange) ? autre : prefab;
            float regard = hasard.nextFloat() * TAU;
            EntityRef bete = Spawns.spawn(entityManager, quoi, new Vector3f(mx, msol, mz),
                    (float) Math.sin(regard), (float) Math.cos(regard));
            bete.addComponent(new SauvageComponent());
            HomeComponent foyer = bete.getComponent(HomeComponent.class);
            if (foyer != null) {
                foyer.point = new Vector3f(mx, msol, mz);
                foyer.pose = true;
                foyer.bande = foyer.suit > 0f ? bande : 0L;
                bete.saveComponent(foyer);
            }
            nes++;
        }
        return nes;
    }

    // --- ce qu'on peut en lire ----------------------------------------------------------------------

    @Override
    public int total() {
        int n = 0;
        for (EntityRef ignored : entityManager.getEntitiesWith(SauvageComponent.class)) {
            n++;
        }
        return n;
    }

    @Override
    public int plafond() {
        return PLAFOND;
    }

    @Override
    public Map<String, Integer> recensement(Vector3f autour, float rayon2) {
        Map<String, Integer> comptes = new HashMap<>();
        float carre = rayon2 * rayon2;
        for (EntityRef creature : entityManager.getEntitiesWith(SauvageComponent.class,
                CreatureComponent.class, LocationComponent.class)) {
            CreatureComponent espece = creature.getComponent(CreatureComponent.class);
            LocationComponent location = creature.getComponent(LocationComponent.class);
            if (espece == null || location == null) {
                continue;
            }
            Vector3f p = location.getWorldPosition(new Vector3f());
            if (!p.isFinite()) {
                continue;
            }
            float dx = p.x - autour.x;
            float dz = p.z - autour.z;
            if (dx * dx + dz * dz <= carre) {
                comptes.merge(espece.species, 1, Integer::sum);
            }
        }
        return comptes;
    }

    @Override
    public List<String> journal() {
        return List.copyOf(journal);
    }

    @Override
    public float[] rayons() {
        return new float[] {ANNEAU_PRES * rayon, ANNEAU_LOIN * rayon, OUBLI * rayon, FOULE * rayon};
    }

    private int noter(String verdict) {
        journal.addFirst(verdict);
        while (journal.size() > MEMOIRE) {
            journal.removeLast();
        }
        return 0;
    }

    private static float distance(Vector3f position, List<Vector3f> points) {
        float plusProche = Float.POSITIVE_INFINITY;
        for (Vector3f point : points) {
            float dx = point.x - position.x;
            float dz = point.z - position.z;
            plusProche = Math.min(plusProche, (float) Math.sqrt(dx * dx + dz * dz));
        }
        return plusProche;
    }
}
