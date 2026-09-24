// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0

package org.terasology.subsystem.inspect;

import org.joml.Vector3f;
import org.joml.Vector3i;
import org.terasology.engine.context.Context;
import org.terasology.engine.logic.players.LocalPlayer;
import org.terasology.engine.world.WorldProvider;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.block.BlockManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Poser des blocs sans passer par la main du joueur.
 * <p>
 * <b>Pourquoi cette route existe.</b> Tout ce qui posait un bloc jusqu'ici passait par le
 * personnage : viser, tenir l'objet, etre a portee, et le serveur verifiait les trois. C'est la
 * bonne regle pour un joueur et c'est un mur pour qui doit batir un decor d'essai — une arene, un
 * mur temoin, un sol plat sous une bete — ou poser un repere a un endroit precis. Ici, le monde
 * est ecrit directement : pas d'inventaire consomme, pas de portee, pas de visee.
 * <p>
 * <b>C'est une porte d'ecriture, et elle a sa propre clef.</b> {@code --inspect-allow-write}, et
 * non celle de la console : les deux s'arment separement parce qu'elles n'ont pas le meme usage —
 * une session qui lit l'etat n'a aucune raison de pouvoir refaire le terrain. Reserve a l'outillage
 * d'agent, sur la loopback, comme tout ce canal.
 * <p>
 * <b>{@code setBlock} est le bon niveau.</b> {@code WorldProvider} le fait suivre a
 * {@code EntityAwareWorldProvider}, qui emet {@code OnChangedBlock} et tient les entites de bloc :
 * un bloc pose par ici vit exactement comme un bloc pose a la main. Il rend {@code null} quand le
 * chunk n'est pas charge — ce refus est compte et rendu, jamais avale, sans quoi un mur bati
 * au bord du monde charge aurait des trous que rien n'expliquerait.
 * <p>
 * <b>Et un nom de bloc inconnu rend de l'air, en silence.</b> C'est le contrat de
 * {@code BlockManager.getBlock}, qui journalise et degrade. Poser mille blocs d'air a la place d'un
 * mur serait une destruction pure, donc le nom est verifie avant la premiere ecriture et la requete
 * entiere est refusee — sauf si c'est bien de l'air qu'on a demande.
 */
public final class BuildRoute {

    /** Plafond par requete. Un remplissage plus gros se decoupe, et se voit passer. */
    private static final int MAX_BLOCS = 4096;

    private BuildRoute() {
    }

    /**
     * Trois formes, une seule route :
     * <ul>
     * <li>{@code ?x&y&z&uri} — un bloc ;</li>
     * <li>{@code ?x&y&z&x2&y2&z2&uri} — le pave plein entre les deux coins, bornes comprises ;</li>
     * <li>un corps POST, une pose par ligne : {@code x y z uri}, ou {@code uri} peut manquer et
     *     vaut alors celui de la requete. C'est la forme qui sert a batir autre chose qu'une
     *     boite.</li>
     * </ul>
     * {@code rel=player} compte les coordonnees depuis le bloc ou se tient le joueur, ce qui evite
     * d'aller relire {@code /view} avant chaque pose.
     */
    public static InspectResponse place(Context context, Map<String, String> params, String body) {
        WorldProvider world = context.get(WorldProvider.class);
        BlockManager blocks = context.get(BlockManager.class);
        if (world == null || blocks == null) {
            return InspectResponse.text(409, "error=no-world reason=aucun monde charge\n");
        }

        Vector3i origine = new Vector3i();
        if ("player".equals(params.get("rel"))) {
            LocalPlayer localPlayer = context.get(LocalPlayer.class);
            if (localPlayer == null || !localPlayer.isValid()) {
                return InspectResponse.text(409, "error=no-player reason=aucun joueur local\n");
            }
            Vector3f position = localPlayer.getPosition(new Vector3f());
            origine.set((int) Math.floor(position.x), (int) Math.floor(position.y),
                    (int) Math.floor(position.z));
        }

        List<Pose> poses = new ArrayList<>();
        try {
            if (body != null && !body.isBlank()) {
                lignes(poses, body, params.get("uri"));
            } else {
                boite(poses, params);
            }
        } catch (BadParam e) {
            return InspectResponse.badParam(e.nom, e.raison);
        }

        if (poses.isEmpty()) {
            return InspectResponse.badParam("x", "aucune pose : donner x,y,z ou un corps POST");
        }
        if (poses.size() > MAX_BLOCS) {
            return InspectResponse.badParam("count", String.format(Locale.ROOT,
                    "%d blocs demandes, plafond %d — decouper la requete", poses.size(), MAX_BLOCS));
        }

        // Tous les noms sont resolus avant la premiere ecriture : un nom fautif en fin de liste
        // laisserait sinon un ouvrage a moitie bati derriere un refus, ce qui est le pire des deux.
        List<Block> resolus = new ArrayList<>(poses.size());
        for (Pose demande : poses) {
            Block block = resoudre(blocks, demande.uri);
            if (block == null) {
                return InspectResponse.text(404, String.format(Locale.ROOT,
                        "error=unknown-block uri=%s reason=rendrait de l'air, rien n'a ete pose%n",
                        demande.uri));
            }
            resolus.add(block);
        }

        int pose = 0;
        int horsChunk = 0;
        int inchange = 0;
        Vector3i premierRefus = null;
        Vector3i curseur = new Vector3i();

        for (int i = 0; i < poses.size(); i++) {
            Pose demande = poses.get(i);
            Block block = resolus.get(i);
            curseur.set(demande.x + origine.x, demande.y + origine.y, demande.z + origine.z);
            Block avant = world.setBlock(curseur, block);
            if (avant == null) {
                horsChunk++;
                if (premierRefus == null) {
                    premierRefus = new Vector3i(curseur);
                }
            } else if (avant.equals(block)) {
                inchange++;      // deja ce bloc : compte a part, sinon « pose » ment
            } else {
                pose++;
            }
        }

        StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
                "placed=%d unchanged=%d refused=%d total=%d%n",
                pose, inchange, horsChunk, poses.size()));
        if (premierRefus != null) {
            out.append(String.format(Locale.ROOT,
                    "-- refus : chunk non charge, a partir de %d,%d,%d%n",
                    premierRefus.x, premierRefus.y, premierRefus.z));
        }
        return InspectResponse.ok(out.toString());
    }

    /**
     * Le nom demande, ou {@code null} s'il ne repond pas. {@code getBlock} rend de l'air pour tout
     * ce qu'il ne connait pas, donc l'air demande explicitement est le seul air acceptable.
     */
    private static Block resoudre(BlockManager blocks, String uri) {
        Block block = blocks.getBlock(uri);
        if (block == null) {
            return null;
        }
        boolean airDemande = uri.equalsIgnoreCase(BlockManager.AIR_ID.toString())
                || uri.equalsIgnoreCase("air");
        return block.getURI().equals(BlockManager.AIR_ID) && !airDemande ? null : block;
    }

    private static void boite(List<Pose> poses, Map<String, String> params) throws BadParam {
        String uri = params.get("uri");
        if (uri == null || uri.isBlank()) {
            throw new BadParam("uri", "nom de bloc manquant");
        }
        int x = entier(params, "x");
        int y = entier(params, "y");
        int z = entier(params, "z");
        int x2 = params.containsKey("x2") ? entier(params, "x2") : x;
        int y2 = params.containsKey("y2") ? entier(params, "y2") : y;
        int z2 = params.containsKey("z2") ? entier(params, "z2") : z;

        for (int ix = Math.min(x, x2); ix <= Math.max(x, x2); ix++) {
            for (int iy = Math.min(y, y2); iy <= Math.max(y, y2); iy++) {
                for (int iz = Math.min(z, z2); iz <= Math.max(z, z2); iz++) {
                    poses.add(new Pose(ix, iy, iz, uri));
                    if (poses.size() > MAX_BLOCS) {
                        return;     // le plafond est signale par l'appelant, sur la taille
                    }
                }
            }
        }
    }

    private static void lignes(List<Pose> poses, String body, String defaut) throws BadParam {
        int numero = 0;
        for (String ligne : body.split("\\R")) {
            numero++;
            String propre = ligne.trim();
            if (propre.isEmpty() || propre.startsWith("#")) {
                continue;
            }
            String[] morceaux = propre.split("\\s+");
            if (morceaux.length < 3) {
                throw new BadParam("body", String.format(Locale.ROOT,
                        "ligne %d : attendu « x y z [uri] »", numero));
            }
            String uri = morceaux.length >= 4 ? morceaux[3] : defaut;
            if (uri == null || uri.isBlank()) {
                throw new BadParam("uri", String.format(Locale.ROOT,
                        "ligne %d : pas de bloc, et aucun uri par defaut", numero));
            }
            try {
                poses.add(new Pose(Integer.parseInt(morceaux[0]), Integer.parseInt(morceaux[1]),
                        Integer.parseInt(morceaux[2]), uri));
            } catch (NumberFormatException e) {
                throw new BadParam("body", String.format(Locale.ROOT,
                        "ligne %d : coordonnee illisible", numero));
            }
            if (poses.size() > MAX_BLOCS) {
                return;
            }
        }
    }

    private static int entier(Map<String, String> params, String nom) throws BadParam {
        String brut = params.get(nom);
        if (brut == null || brut.isBlank()) {
            throw new BadParam(nom, "coordonnee manquante");
        }
        try {
            return Integer.parseInt(brut.trim());
        } catch (NumberFormatException e) {
            throw new BadParam(nom, "entier attendu");
        }
    }

    private static final class Pose {
        private final int x;
        private final int y;
        private final int z;
        private final String uri;

        private Pose(int x, int y, int z, String uri) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.uri = uri;
        }
    }

    private static final class BadParam extends Exception {
        private final String nom;
        private final String raison;

        private BadParam(String nom, String raison) {
            super(nom);
            this.nom = nom;
            this.raison = raison;
        }
    }
}
