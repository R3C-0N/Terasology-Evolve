// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0

package org.terasology.subsystem.inspect;

import org.joml.Vector3f;
import org.terasology.engine.context.Context;
import org.terasology.engine.core.Time;
import org.terasology.engine.entitySystem.entity.EntityManager;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.metadata.MetadataUtil;
import org.terasology.engine.logic.characters.CharacterHeldItemComponent;
import org.terasology.engine.logic.location.LocationComponent;
import org.terasology.engine.logic.players.LocalPlayer;
import org.terasology.gestalt.entitysystem.component.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * La bande : ce qui bouge et ce qui est fait, echantillonne image par image.
 * <p>
 * <b>Le besoin.</b> Une question comme « de combien recule un loup frappe ? » ne se lit ni sur une
 * capture d'ecran, ni sur deux appels a {@code /entity} separes d'une seconde : le mouvement dure
 * quatre dixiemes de seconde, et le canal repond en vingt millisecondes <em>quand on pense a
 * demander</em>. Il fallait un magnetophone — on l'arme, on frappe, on l'arrete, et on relit.
 * <p>
 * <b>Tout se passe sur le thread de jeu.</b> {@code start}, {@code stop}, {@code mark} et
 * {@code dump} arrivent par le sas ({@link InspectBridge}), et {@code tick} est appele par
 * {@code postUpdate} : il n'y a donc aucun verrou ici, et aucune liste n'est jamais parcourue
 * pendant qu'un autre thread l'ecrit.
 * <p>
 * <b>Les evenements sont deduits, pas ecoutes.</b> Ce sous-systeme n'a pas le droit d'etre un
 * systeme ECS — voir l'avertissement de {@link InspectSubsystem} —, donc rien ici ne recoit
 * {@code AttackEvent} ni {@code OnDamagedEvent}. Les trois sources sont des <em>differences</em>
 * relevees d'une image a l'autre : {@code CharacterHeldItemComponent.lastItemUsedTime} change
 * quand un coup part <em>et passe le verrou de cadence</em>, une sonde numerique change quand la
 * vie tombe, un composant apparait ou disparait. C'est moins riche qu'un evenement, et c'est plus
 * honnete : on enregistre ce que le monde montre, pas ce qu'on croit qu'il envoie.
 * <p>
 * <b>Les marques viennent d'ailleurs.</b> Ce que le joueur fait au clavier et a la souris n'est
 * visible nulle part dans l'etat du monde — un clic qui rate n'y laisse rien. C'est donc le pilote
 * qui le dit, par {@code POST /record/mark}, au moment ou il l'envoie. Une marque est horodatee au
 * tick suivant son arrivee, soit au plus une image de retard : assez pour caler une action contre
 * le mouvement, pas assez pour departager deux images.
 * <p>
 * <b>Les noms de composants sont des noms courts.</b> {@code Health}, pas
 * {@code HealthComponent} : c'est la convention deja etablie par le filtre {@code with=} de
 * {@link EntityRoutes}, et un module ne peut de toute facon pas etre reference ici — ses classes
 * vivent dans un autre chargeur. Les sondes passent donc par la reflexion, sur des champs publics,
 * et un champ introuvable se dit une fois au lieu de faire tomber la bande.
 */
public final class Recorder {

    /** Au-dela, la bande s'arrete d'elle-meme : une fuite de memoire silencieuse serait pire. */
    private static final int MAX_FRAMES = 6000;
    private static final int MAX_EVENTS = 800;

    /** Sujets suivis. Plus haut, une image ne tiendrait plus sur une ligne lisible. */
    private static final int MAX_SUJETS = 16;

    private static final float DEFAUT_HZ = 20f;
    private static final float MAX_HZ = 120f;

    /** Ce qu'une sonde doit bouger pour valoir un evenement. */
    private static final double EPSILON = 1e-3;

    private final List<String> frames = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<Sujet> sujets = new ArrayList<>();

    /** Champs de sonde deja cherches. Un echec est retenu comme un succes, voir {@link #lire}. */
    private final Map<String, Field> champs = new HashMap<>();

    private boolean recording;
    private boolean sature;
    private long debutMs;
    private long derniereImageMs;
    private long periodeMs;
    private float hz = DEFAUT_HZ;
    private float horloge;
    private List<String> drapeaux = List.of();
    private List<String> sondes = List.of();

    private EntityRef joueur = EntityRef.NULL;
    private long dernierUsage;

    /**
     * Arme la bande. Le lot de sujets est fige ici : une bete qui meurt garde son identifiant —
     * le cadavre <em>est</em> la creature —, et un lot rescanne a chaque image ferait entrer et
     * sortir des lignes au milieu de la bande sans qu'on puisse les recoller.
     */
    public boolean start(Context context, Map<String, String> params) {
        EntityManager entityManager = context.get(EntityManager.class);
        LocalPlayer localPlayer = context.get(LocalPlayer.class);
        Time time = context.get(Time.class);
        if (entityManager == null || localPlayer == null || !localPlayer.isValid() || time == null) {
            return false;
        }

        frames.clear();
        events.clear();
        sujets.clear();
        champs.clear();
        sature = false;
        horloge = 0f;

        hz = Math.max(1f, Math.min(MAX_HZ, nombre(params.get("hz"), DEFAUT_HZ)));
        periodeMs = Math.max(1L, (long) (1000f / hz));
        drapeaux = decouper(params.get("flags"));
        sondes = decouper(params.getOrDefault("probes", "Health.currentHealth"));

        joueur = localPlayer.getCharacterEntity();
        CharacterHeldItemComponent tenu = joueur.getComponent(CharacterHeldItemComponent.class);
        dernierUsage = tenu == null ? 0L : tenu.lastItemUsedTime;

        for (EntityRef entity : choisir(entityManager, localPlayer, params)) {
            sujets.add(new Sujet(entity));
        }

        debutMs = time.getGameTimeInMs();
        derniereImageMs = debutMs - periodeMs;
        recording = true;
        // Le premier releve part tout de suite : sans lui, l'origine du temps serait un instant
        // dont on n'a aucune mesure.
        tick(context);
        return true;
    }

    public void stop() {
        recording = false;
    }

    /**
     * Une action du pilote, telle que le pilote la raconte. Refusee bande a l'arret : une marque
     * sans image a laquelle se rattacher n'apprend rien.
     */
    public boolean mark(String label) {
        if (!recording) {
            return false;
        }
        evenement("mark " + (label.isBlank() ? "-" : label));
        return true;
    }

    public boolean isRecording() {
        return recording;
    }

    /**
     * Une image, et les differences depuis la precedente.
     * <p>
     * Les evenements sont releves a <em>chaque</em> tick, jamais a la cadence des images : un coup
     * porte entre deux releves disparaitrait purement et simplement, et c'est precisement ce qu'on
     * enregistre. Seule la ligne de position obeit a {@code hz}.
     */
    public void tick(Context context) {
        if (!recording) {
            return;
        }
        Time time = context.get(Time.class);
        if (time == null || context.get(EntityManager.class) == null) {
            // Le monde a ete decharge sous la bande. On la ferme en le disant, plutot que de
            // continuer a ecrire des positions qui ne veulent plus rien dire.
            evenement("world-gone");
            recording = false;
            return;
        }
        long maintenant = time.getGameTimeInMs();
        horloge = (maintenant - debutMs) / 1000f;

        usage();
        for (Sujet sujet : sujets) {
            differences(sujet);
        }

        if (maintenant - derniereImageMs < periodeMs) {
            return;
        }
        derniereImageMs = maintenant;

        StringBuilder ligne = new StringBuilder(String.format(Locale.ROOT, "f %.3f ", horloge));
        ligne.append(poseJoueur(context.get(LocalPlayer.class)));
        for (Sujet sujet : sujets) {
            ligne.append(" | ").append(pose(Long.toString(sujet.id), sujet.entity, false));
        }
        ajouter(frames, ligne.toString(), MAX_FRAMES);
    }

    /** L'entete : de quoi savoir ce qu'on relit avant de le relire. */
    public String entete() {
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
                "rec state=%s hz=%.1f frames=%d events=%d duration=%.3f full=%b%n",
                recording ? "recording" : "stopped", hz, frames.size(), events.size(),
                horloge, sature));
        out.append(String.format(Locale.ROOT, "subject ref=P id=%d prefab=%s%n",
                joueur.getId(), prefab(joueur)));
        for (Sujet sujet : sujets) {
            out.append(String.format(Locale.ROOT, "subject ref=%d prefab=%s%n",
                    sujet.id, sujet.prefab));
        }
        out.append("probes=").append(sondes.isEmpty() ? "-" : String.join(",", sondes))
           .append(" flags=").append(drapeaux.isEmpty() ? "-" : String.join(",", drapeaux))
           .append('\n');
        return out.toString();
    }

    /**
     * Relit la bande, par pages. La page se ferme sur un budget d'octets plutot que sur un nombre
     * de lignes, et elle dit ou reprendre : le plafond de reponse du serveur couperait sinon une
     * ligne en deux, et une position tronquee se lit comme une position.
     */
    public String dump(int from, int budget) {
        StringBuilder out = new StringBuilder(entete());
        for (String event : events) {
            out.append(event).append('\n');
        }
        int index = Math.max(0, from);
        while (index < frames.size() && out.length() < budget) {
            out.append(frames.get(index)).append('\n');
            index++;
        }
        out.append(String.format(Locale.ROOT, "-- frames %d..%d sur %d%s%n",
                Math.max(0, from), index, frames.size(),
                index < frames.size() ? " next=" + index : " fin"));
        return out.toString();
    }

    /** La bande entiere, d'un bloc, pour l'ecrire dans un fichier. */
    public String tout() {
        StringBuilder out = new StringBuilder(entete());
        for (String event : events) {
            out.append(event).append('\n');
        }
        for (String frame : frames) {
            out.append(frame).append('\n');
        }
        return out.toString();
    }

    // --- le detail -------------------------------------------------------------------------

    /**
     * Les sujets : soit une liste d'identifiants, soit tout ce qui porte un composant dans un
     * rayon. Le second cas est celui qu'on emploie — on ne connait pas l'identifiant d'un loup
     * avant de l'avoir cherche.
     */
    private List<EntityRef> choisir(EntityManager entityManager, LocalPlayer localPlayer,
                                    Map<String, String> params) {
        List<EntityRef> retenus = new ArrayList<>();
        String ids = params.get("ids");
        if (ids != null && !ids.isBlank()) {
            for (String brut : ids.split(",")) {
                if (retenus.size() >= MAX_SUJETS) {
                    break;
                }
                try {
                    long id = Long.parseLong(brut.trim());
                    if (entityManager.contains(id)) {
                        retenus.add(entityManager.getEntity(id));
                    }
                } catch (NumberFormatException ignored) {
                    // Un identifiant illisible est saute : une bande partielle vaut mieux qu'un refus.
                }
            }
            return retenus;
        }

        String avec = params.get("with");
        float rayon = nombre(params.get("r"), 32f);
        Vector3f centre = localPlayer.getPosition(new Vector3f());
        Vector3f scratch = new Vector3f();
        for (EntityRef entity : entityManager.getAllEntities()) {
            if (retenus.size() >= MAX_SUJETS) {
                break;
            }
            LocationComponent location = entity.getComponent(LocationComponent.class);
            if (location == null || entity.equals(joueur)) {
                continue;
            }
            if (avec != null && !porte(entity, avec)) {
                continue;
            }
            if (location.getWorldPosition(scratch).distance(centre) > rayon) {
                continue;
            }
            retenus.add(entity);
        }
        return retenus;
    }

    /** Un coup part, et il est passe : {@code lastItemUsedTime} n'avance que du bon cote du verrou. */
    private void usage() {
        CharacterHeldItemComponent tenu = joueur.getComponent(CharacterHeldItemComponent.class);
        if (tenu == null || tenu.lastItemUsedTime == dernierUsage) {
            return;
        }
        dernierUsage = tenu.lastItemUsedTime;
        evenement(String.format(Locale.ROOT, "use item=%s cooldown=%.3f",
                prefab(tenu.selectedItem), (tenu.nextItemUseTime - tenu.lastItemUsedTime) / 1000f));
    }

    private void differences(Sujet sujet) {
        if (!sujet.entity.exists()) {
            if (!sujet.parti) {
                sujet.parti = true;
                evenement("gone " + sujet.id);
            }
            return;
        }
        for (String sonde : sondes) {
            Double valeur = lire(sujet.entity, sonde);
            if (valeur == null) {
                continue;
            }
            Double avant = sujet.valeurs.put(sonde, valeur);
            if (avant != null && Math.abs(avant - valeur) > EPSILON) {
                evenement(String.format(Locale.ROOT, "probe %d %s %.2f->%.2f",
                        sujet.id, court(sonde), avant, valeur));
            }
        }
        for (String drapeau : drapeaux) {
            boolean present = porte(sujet.entity, drapeau);
            if (present == sujet.drapeaux.contains(drapeau)) {
                continue;
            }
            if (present) {
                sujet.drapeaux.add(drapeau);
            } else {
                sujet.drapeaux.remove(drapeau);
            }
            evenement(String.format(Locale.ROOT, "flag %d %s%s",
                    sujet.id, present ? "+" : "-", drapeau));
        }
    }

    /**
     * Le joueur se releve autrement que les betes : son <em>regard</em> n'est pas dans sa
     * {@code LocationComponent}, qui ne porte que le cap. Le tangage vit dans la camera, et c'est
     * lui qui decide ou part un coup — une bande qui dirait toujours zero ferait manquer toutes les
     * visees. On lit donc {@code LocalPlayer}, exactement comme {@code /view}.
     */
    private String poseJoueur(LocalPlayer localPlayer) {
        if (localPlayer == null || !localPlayer.isValid()) {
            return pose("P", joueur, false);
        }
        Vector3f position = localPlayer.getPosition(new Vector3f());
        Vector3f regard = localPlayer.getViewDirection(new Vector3f());
        double y = Math.max(-1.0, Math.min(1.0, regard.y));
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
                "P %.3f,%.3f,%.3f %.1f %.1f", position.x, position.y, position.z,
                Math.toDegrees(Math.atan2(regard.x, regard.z)), -Math.toDegrees(Math.asin(y))));
        for (String sonde : sondes) {
            Double valeur = lire(joueur, sonde);
            if (valeur != null) {
                out.append(String.format(Locale.ROOT, " %s=%.2f", court(sonde), valeur));
            }
        }
        return out.toString();
    }

    /** Position, cap, et ce qui a ete demande en plus. */
    private String pose(String ref, EntityRef entity, boolean tangage) {
        if (!entity.exists()) {
            return ref + " gone";
        }
        LocationComponent location = entity.getComponent(LocationComponent.class);
        if (location == null) {
            return ref + " noloc";
        }
        Vector3f position = location.getWorldPosition(new Vector3f());
        Vector3f direction = location.getWorldDirection(new Vector3f());
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
                "%s %.3f,%.3f,%.3f %.1f", ref, position.x, position.y, position.z,
                Math.toDegrees(Math.atan2(direction.x, direction.z))));
        if (tangage) {
            double y = Math.max(-1.0, Math.min(1.0, direction.y));
            out.append(String.format(Locale.ROOT, " %.1f", -Math.toDegrees(Math.asin(y))));
        }
        for (String sonde : sondes) {
            Double valeur = lire(entity, sonde);
            if (valeur != null) {
                out.append(String.format(Locale.ROOT, " %s=%.2f", court(sonde), valeur));
            }
        }
        for (String drapeau : drapeaux) {
            if (porte(entity, drapeau)) {
                out.append(" +").append(drapeau);
            }
        }
        return out.toString();
    }

    /**
     * Lit un champ numerique public par reflexion — {@code Health.currentHealth}.
     * <p>
     * Par reflexion parce qu'il n'y a pas d'autre voie : {@code HealthComponent} appartient a un
     * module, charge par un chargeur de classes que ce sous-systeme ne voit pas a la compilation.
     * L'echec est retenu en cache comme un succes, sinon un nom mal ecrit couterait une recherche
     * de champ par entite et par image.
     */
    private Double lire(EntityRef entity, String sonde) {
        int point = sonde.indexOf('.');
        if (point <= 0) {
            return null;
        }
        String composant = sonde.substring(0, point);
        String nom = sonde.substring(point + 1);
        for (Component component : entity.iterateComponents()) {
            if (!MetadataUtil.getComponentClassName(component.getClass()).equals(composant)) {
                continue;
            }
            String cle = component.getClass().getName() + '#' + nom;
            Field champ = champs.get(cle);
            if (champ == null && !champs.containsKey(cle)) {
                try {
                    champ = component.getClass().getField(nom);
                } catch (NoSuchFieldException e) {
                    champ = null;
                }
                champs.put(cle, champ);
            }
            if (champ == null) {
                return null;
            }
            try {
                Object valeur = champ.get(component);
                return valeur instanceof Number ? ((Number) valeur).doubleValue() : null;
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean porte(EntityRef entity, String court) {
        for (Component component : entity.iterateComponents()) {
            if (MetadataUtil.getComponentClassName(component.getClass()).equals(court)) {
                return true;
            }
        }
        return false;
    }

    private void evenement(String texte) {
        ajouter(events, String.format(Locale.ROOT, "ev %.3f %s", horloge, texte), MAX_EVENTS);
    }

    /** Le plafond ferme la bande plutot que de la laisser grossir sans fin, et il le dit. */
    private void ajouter(List<String> cible, String ligne, int plafond) {
        if (cible.size() >= plafond) {
            sature = true;
            recording = false;
            return;
        }
        cible.add(ligne);
    }

    private static String court(String sonde) {
        int point = sonde.indexOf('.');
        return point < 0 ? sonde : sonde.substring(point + 1);
    }

    private static String prefab(EntityRef entity) {
        return entity.getParentPrefab() == null ? "-" : entity.getParentPrefab().getUrn().toString();
    }

    private static List<String> decouper(String brut) {
        if (brut == null || brut.isBlank() || "-".equals(brut.trim())) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String morceau : brut.split(",")) {
            if (!morceau.isBlank()) {
                out.add(morceau.trim());
            }
        }
        return out;
    }

    private static float nombre(String brut, float defaut) {
        if (brut == null || brut.isBlank()) {
            return defaut;
        }
        try {
            return Float.parseFloat(brut.trim());
        } catch (NumberFormatException e) {
            return defaut;
        }
    }

    /**
     * Un suivi : l'entite, son nom et son identifiant au moment de l'armement, et ce qu'on lui a vu
     * de different. L'identifiant est copie parce qu'une entite detruite rend {@code NULL_ID}, et
     * la bande perdrait alors le nom de la ligne au moment ou il compte le plus.
     */
    private static final class Sujet {
        private final EntityRef entity;
        private final long id;
        private final String prefab;
        private final Map<String, Double> valeurs = new HashMap<>();
        private final Set<String> drapeaux = new LinkedHashSet<>();
        private boolean parti;

        private Sujet(EntityRef entity) {
            this.entity = entity;
            this.id = entity.getId();
            this.prefab = Recorder.prefab(entity);
        }
    }
}
