// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.config.Config;
import org.terasology.engine.core.GameEngine;
import org.terasology.engine.core.PathManager;
import org.terasology.engine.core.modes.StateLoading;
import org.terasology.engine.game.GameManifest;
import org.terasology.engine.network.NetworkMode;
import org.terasology.engine.registry.CoreRegistry;
import org.terasology.engine.rendering.nui.layers.mainMenu.savedGames.GameInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Charge une partie sauvegardee, en solo ou en serveur.
 *
 * <p>Trois ecrans lancent desormais une sauvegarde — le panneau « Reprendre » du menu principal,
 * la liste des parties, et l'onglet d'hebergement du multijoueur. La logique est ici, en un seul
 * endroit : elle porte la migration des listes d'autorisation, que seul le mode serveur declenche
 * et qu'il serait facile d'oublier en la recopiant.
 */
public final class GameLauncher {

    private static final Logger logger = LoggerFactory.getLogger(GameLauncher.class);

    private GameLauncher() {
    }

    /**
     * @throws Exception ce que la construction de l'etat de chargement a leve ; l'appelant en fait
     *         une popup, car lui seul sait sur quel ecran l'afficher.
     */
    public static void launch(GameInfo gameInfo, Config config, boolean asServer) throws Exception {
        if (asServer) {
            prepareServerLists();
        }
        GameManifest manifest = gameInfo.getManifest();
        config.getWorldGeneration().setDefaultSeed(manifest.getSeed());
        config.getWorldGeneration().setWorldTitle(manifest.getTitle());
        Optional.ofNullable(CoreRegistry.get(GameEngine.class))
                .orElseThrow(() -> new IllegalStateException("Failed to get game engine from CoreRegistry"))
                .changeState(new StateLoading(manifest, asServer ? NetworkMode.DEDICATED_SERVER : NetworkMode.NONE));
    }

    /**
     * Migre chaque fichier hérité independamment avant d'en creer de vides, pour ne pas jeter en
     * silence les identifiants deja bannis ou autorises lors d'une mise a jour.
     */
    private static void prepareServerLists() {
        Path homePath = PathManager.getInstance().getHomePath();
        migrate(homePath.resolve("blacklist.json"), homePath.resolve("denylist.json"));
        migrate(homePath.resolve("whitelist.json"), homePath.resolve("allowlist.json"));
        touch(homePath.resolve("denylist.json"));
        touch(homePath.resolve("allowlist.json"));
    }

    private static void migrate(Path legacy, Path target) {
        try {
            if (Files.exists(legacy) && !Files.exists(target)) {
                Files.move(legacy, target);
            }
        } catch (IOException e) {
            logger.error("Failed to migrate {} to {}", legacy.getFileName(), target.getFileName(), e);
        }
    }

    private static void touch(Path path) {
        if (!Files.exists(path)) {
            try {
                Files.createFile(path);
            } catch (IOException e) {
                logger.error("IO Exception on {} generation", path.getFileName(), e);
            }
        }
    }
}
