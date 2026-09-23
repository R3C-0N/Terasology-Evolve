// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.joml.Vector3f;
import org.terasology.engine.entitySystem.entity.EntityManager;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.prefab.Prefab;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.logic.console.commandSystem.annotations.Command;
import org.terasology.engine.logic.console.commandSystem.annotations.CommandParam;
import org.terasology.engine.logic.console.commandSystem.annotations.Sender;
import org.terasology.engine.logic.delay.DelayedActionTriggeredEvent;
import org.terasology.engine.logic.location.LocationComponent;
import org.terasology.engine.logic.permission.PermissionManager;
import org.terasology.engine.network.ClientComponent;
import org.terasology.engine.registry.In;

import java.util.Map;
import java.util.Optional;

/**
 * What the world is doing with its wildlife, on demand.
 * <p>
 * A species that never appears and a species that appears rarely look exactly the same from inside the game,
 * and the difference is usually a typo in a biome name. So the report carries the last verdicts, not only the
 * counts: {@code sans biome}, {@code sol}, {@code liquide}, {@code cerf : terrain} each name the test that
 * refused the spot.
 * <p>
 * These live apart from {@link BestiaryCommands}, whose own javadoc argues that a creature reaches the world
 * by exactly two roads — a totem and a command. The world laying one down by itself is a third, and it
 * deserves its own file rather than quietly falsifying that sentence.
 */
@RegisterSystem
public class FauneCommands extends BaseComponentSystem {

    @In
    private Faune faune;

    @In
    private Bestiary bestiary;

    @In
    private EntityManager entityManager;

    @Command(shortDescription = "Dit ce que le monde a posé, et pourquoi il a refusé le reste",
            helpText = "Sans argument : le recensement autour de vous et les derniers verdicts du peupleur. "
                    + "Avec une espèce : son habitat, tel que le serveur l'a lu.",
            runOnServer = true,
            requiredPermission = PermissionManager.CHEAT_PERMISSION)
    public String faune(@Sender EntityRef client,
                        @CommandParam(value = "espèce", required = false) String espece) {
        if (espece != null && !espece.isEmpty()) {
            return habitat(espece);
        }
        EntityRef personnage = personnage(client);
        LocationComponent location = personnage.getComponent(LocationComponent.class);
        if (location == null) {
            return "Aucun personnage";
        }
        float[] rayons = faune.rayons();
        Map<String, Integer> autour = faune.recensement(location.getWorldPosition(new Vector3f()), rayons[3]);

        StringBuilder texte = new StringBuilder();
        texte.append(String.format("Sauvages : %d sur %d — anneau %.0f à %.0f, oubli %.0f, foule %.0f%n",
                faune.total(), faune.plafond(), rayons[0], rayons[1], rayons[2], rayons[3]));
        if (autour.isEmpty()) {
            texte.append("Personne autour de vous.").append(System.lineSeparator());
        } else {
            autour.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> texte.append(String.format("  %-14s %d%n", e.getKey(), e.getValue())));
        }
        texte.append("Derniers essais :").append(System.lineSeparator());
        if (faune.journal().isEmpty()) {
            texte.append("  (aucun)");
        } else {
            faune.journal().forEach(v -> texte.append("  ").append(v).append(System.lineSeparator()));
        }
        return texte.toString().stripTrailing();
    }

    @Command(shortDescription = "Force des essais de peuplement, tout de suite",
            helpText = "Le peupleur tente un lieu toutes les cinq secondes ; attendre un ours prendrait une "
                    + "heure. Cette commande ne contourne aucun test : elle ne fait qu'avancer l'horloge.",
            runOnServer = true,
            requiredPermission = PermissionManager.CHEAT_PERMISSION)
    public String faunePas(@CommandParam(value = "essais", required = false) Integer essais) {
        int combien = essais == null ? 10 : Math.max(1, Math.min(200, essais));
        int nes = faune.peupler(combien);
        return String.format("%d essais, %d bêtes", combien, nes);
    }

    @Command(shortDescription = "Fait repousser tout de suite ce qui a été brouté",
            helpText = "La repousse court sur le temps de JEU, pas sur celui du monde : setWorldTime ne "
                    + "l'avance pas, et sans cette commande le brout serait invérifiable en une séance.",
            runOnServer = true,
            requiredPermission = PermissionManager.CHEAT_PERMISSION)
    public String repousse() {
        int n = 0;
        for (EntityRef marqueur : entityManager.getEntitiesWith(RegrowthComponent.class)) {
            marqueur.send(new DelayedActionTriggeredEvent("Bestiaire:repousse"));
            n++;
        }
        return n + " cellules rendues";
    }

    private String habitat(String espece) {
        Optional<Prefab> prefab = bestiary.creature(espece);
        if (prefab.isEmpty()) {
            return "Espèce inconnue. Le bestiaire porte : " + String.join(", ", bestiary.species());
        }
        HabitatComponent h = prefab.get().getComponent(HabitatComponent.class);
        if (h == null) {
            return espece + " n'a pas d'habitat : le monde ne la pose jamais, elle sort d'un totem.";
        }
        return String.format("%s%n  biomes   %s%n  densité  %.2f%n  groupe   %d à %d, écart %.1f"
                        + "%n  heures   %.2f à %.2f%n  plafond  %d dans la foule"
                        + "%n  couvert  %s%n  eau      %.1f%n  ombre    %d%n  exige    %d"
                        + "%n  mélange  %s (%.0f %%)",
                espece, h.biomes, h.densite, h.groupeMin, h.groupeMax, h.ecart,
                h.heureDe, h.heureA, h.plafond,
                h.couvert.isEmpty() ? "(éteint)" : h.couvert.toString(),
                h.eau, h.ombre, h.exige,
                h.melange.isEmpty() ? "(aucun)" : h.melange, h.chanceMelange * 100f);
    }

    private EntityRef personnage(EntityRef client) {
        ClientComponent composant = client.getComponent(ClientComponent.class);
        return composant == null ? EntityRef.NULL : composant.character;
    }
}
