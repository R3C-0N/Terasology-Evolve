// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.subsystem.inspect;

import com.google.gson.Gson;
import org.joml.Vector3f;
import org.terasology.engine.context.Context;
import org.terasology.engine.entitySystem.entity.EntityManager;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.metadata.MetadataUtil;
import org.terasology.engine.input.cameraTarget.CameraTargetSystem;
import org.terasology.engine.logic.location.LocationComponent;
import org.terasology.engine.logic.players.LocalPlayer;
import org.terasology.engine.persistence.typeHandling.gson.GsonBuilderFactory;
import org.terasology.gestalt.entitysystem.component.Component;
import org.terasology.gestalt.assets.management.AssetManager;
import org.terasology.persistence.typeHandling.TypeHandlerLibrary;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Les routes d'entites : {@code /entities} (la liste) et {@code /entity/...} (les valeurs).
 * <p>
 * Deux etages, et c'est l'etagement qui tient le volume : la liste ne donne que les <em>noms</em>
 * des composants, jamais leurs valeurs. Un millier d'entites tiendrait sinon difficilement dans une
 * fenetre de contexte.
 */
public final class EntityRoutes {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private EntityRoutes() {
    }

    /**
     * Une ligne par entite : identifiant, prefab, position, noms de composants.
     * <p>
     * {@code radius} filtre autour du joueur ; {@code with} filtre sur un nom de composant.
     */
    public static InspectResponse entities(Context context, Map<String, String> params) {
        EntityManager entityManager = context.get(EntityManager.class);
        if (entityManager == null) {
            return InspectResponse.text(409, "error=no-world reason=aucun gestionnaire d'entites\n");
        }
        int limit = clamp(params.get("limit"), DEFAULT_LIMIT, MAX_LIMIT);
        String with = params.get("with");
        Integer radius = params.containsKey("r") ? clamp(params.get("r"), 32, 4096) : null;

        Vector3f centre = null;
        if (radius != null) {
            LocalPlayer player = context.get(LocalPlayer.class);
            if (player == null || !player.isValid()) {
                return InspectResponse.text(409, "error=no-player reason=aucun joueur local\n");
            }
            centre = player.getPosition(new Vector3f());
        }

        StringBuilder out = new StringBuilder();
        int matched = 0;
        int shown = 0;
        Vector3f scratch = new Vector3f();

        // getAllEntities est paresseux et enjambe trois reservoirs : il se consomme dans ce tour de
        // boucle du thread de jeu, jamais conserve.
        for (EntityRef entity : entityManager.getAllEntities()) {
            List<String> names = new ArrayList<>();
            for (Component component : entity.iterateComponents()) {
                names.add(MetadataUtil.getComponentClassName(component.getClass()));
            }
            if (with != null && !names.contains(with)) {
                continue;
            }
            LocationComponent location = entity.getComponent(LocationComponent.class);
            Vector3f pos = location == null ? null : location.getWorldPosition(scratch);
            if (centre != null && (pos == null || pos.distance(centre) > radius)) {
                continue;
            }
            matched++;
            if (shown >= limit) {
                continue;
            }
            shown++;
            out.append(String.format(Locale.ROOT, "id=%d prefab=%s pos=%s comps=%s%n",
                    entity.getId(), prefabName(entity),
                    pos == null ? "-" : String.format(Locale.ROOT, "%.1f,%.1f,%.1f",
                            pos.x, pos.y, pos.z),
                    String.join(",", names)));
        }
        out.append(String.format(Locale.ROOT, "-- %d affichees sur %d correspondantes, %d actives%n",
                shown, matched, entityManager.getActiveEntityCount()));
        return InspectResponse.ok(out.toString());
    }

    /**
     * Toutes les valeurs de tous les composants d'une entite.
     * <p>
     * <b>Ni {@code toFullDescription()} ni {@code dumpEntities} :</b> tous deux rendent un delta
     * contre le prefab, donc un composant egal a ses valeurs par defaut <em>disparait
     * entierement</em> — l'inspecteur conclurait qu'il n'est pas la.
     */
    public static InspectResponse entity(Context context, String path, Map<String, String> params) {
        EntityManager entityManager = context.get(EntityManager.class);
        if (entityManager == null) {
            return InspectResponse.text(409, "error=no-world reason=aucun gestionnaire d'entites\n");
        }
        EntityRef entity = resolve(context, entityManager, path);
        if (entity == null) {
            return InspectResponse.text(404, String.format(Locale.ROOT,
                    "error=unknown-entity ref=%s reason=alias inconnu ou identifiant absent%n",
                    path));
        }
        if (!entity.exists()) {
            return InspectResponse.text(404, "error=unknown-entity reason=entite inexistante\n");
        }

        TypeHandlerLibrary library = context.get(TypeHandlerLibrary.class);
        Gson gson = library == null ? new Gson()
                : GsonBuilderFactory.createGsonBuilderWithTypeSerializationLibrary(library)
                        // Sans ceci, un champ nul disparait au lieu de se dire nul — et
                        // EntityRefTypeHandler rend null des qu'une reference n'est pas persistante,
                        // si bien qu'un lien qui existe se lirait comme un lien absent.
                        .serializeNulls()
                        .create();

        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "entity id=%d prefab=%s exists=%b persistent=%b%n",
                entity.getId(), prefabName(entity), entity.exists(), entity.isPersistent()));
        for (Component component : entity.iterateComponents()) {
            String name = MetadataUtil.getComponentClassName(component.getClass());
            // Chacun dans son try/catch : un type de champ sans gestionnaire doit degrader en une
            // ligne visible, jamais faire tomber la route entiere.
            try {
                out.append(name).append(' ').append(gson.toJson(component)).append('\n');
            } catch (Exception e) {
                out.append(name).append(" <echec de serialisation: ")
                   .append(e.getClass().getSimpleName()).append(">\n");
            }
        }
        out.append("-- a savoir : une Map dont la cle n'est pas une chaine est omise par Gson\n");
        return InspectResponse.ok(out.toString());
    }

    private static EntityRef resolve(Context context, EntityManager entityManager, String path) {
        LocalPlayer player = context.get(LocalPlayer.class);
        switch (path) {
            case "player":
                return player == null || !player.isValid() ? null : player.getCharacterEntity();
            case "client":
                return player == null || !player.isValid() ? null : player.getClientEntity();
            case "target":
                CameraTargetSystem target = context.get(CameraTargetSystem.class);
                return target == null || !target.isTargetAvailable() ? null : target.getTarget();
            default:
                long id;
                try {
                    id = Long.parseLong(path);
                } catch (NumberFormatException e) {
                    return null;
                }
                // contains() d'abord : getEntity sur un identifiant absent ecrit une ERREUR dans le
                // journal du jeu a chaque appel.
                return entityManager.contains(id) ? entityManager.getEntity(id) : null;
        }
    }

    private static String prefabName(EntityRef entity) {
        return entity.getParentPrefab() == null ? "-" : entity.getParentPrefab().getUrn().toString();
    }

    private static int clamp(String raw, int fallback, int max) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return Math.max(1, Math.min(max, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
