// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.core.PathManager;
import org.terasology.engine.core.module.ModuleManager;
import org.terasology.engine.game.GameManifest;
import org.terasology.engine.registry.In;
import org.terasology.engine.rendering.nui.animation.MenuAnimationSystems;
import org.terasology.engine.rendering.nui.layers.mainMenu.savedGames.GameInfo;
import org.terasology.engine.rendering.nui.layers.mainMenu.savedGames.GameProvider;
import org.terasology.engine.rendering.nui.layers.mainMenu.savedGames.GameSummary;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.nui.databinding.ReadOnlyBinding;
import org.terasology.nui.widgets.UIButton;
import org.terasology.nui.widgets.UILabel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * La liste des parties : un choix a gauche, ce qu'on en sait a droite.
 *
 * <p>Generateur de monde, liste de modules et chemin de sauvegarde ont disparu de cet ecran —
 * le moteur les resout seul. Ne subsistent que les trois faits qui aident a choisir : quand on y
 * a joue, combien de temps, dans quel mode. Le bouton « Details », et avec lui l'entree vers
 * {@code gameDetailsScreen} et {@code moduleDetailsScreen}, n'existe plus.
 */
public class SelectGameScreen extends SelectionScreen {
    public static final ResourceUrn ASSET_URI = new ResourceUrn("engine:selectGameScreen");
    private static final String REMOVE_STRING = "saved game";
    private static final Logger logger = LoggerFactory.getLogger(SelectGameScreen.class);

    @In
    private ModuleManager moduleManager;

    private UniverseWrapper universeWrapper;

    // widgets
    private UILabel gameTypeTitle;
    private UILabel detailTitle;
    private UILabel detailLastPlayed;
    private UILabel detailPlaytime;
    private UILabel detailMode;
    private UIButton load;
    private UIButton delete;
    private UIButton duplicate;
    private UIButton close;
    private UIButton create;

    @Override
    public void initialise() {
        setAnimationSystem(MenuAnimationSystems.createDefaultSwipeAnimation());

        initWidgets();

        if (!isValidScreen()) {
            return;
        }

        gameTypeTitle.bindText(new ReadOnlyBinding<String>() {
            @Override
            public String get() {
                String kind = translationSystem.translate(isLoadingAsServer()
                        ? "${engine:menu#select-multiplayer-game-sub-title}"
                        : "${engine:menu#select-singleplayer-game-sub-title}");
                int count = getGameInfos().getList().size();
                return kind + "  ·  " + count + " " + translationSystem.translate("${engine:menu#games-count}");
            }
        });

        getGameInfos().setItemRenderer(new TwoLineItemRenderer<GameInfo>() {
            @Override
            public String getTitle(GameInfo value) {
                return value.getManifest().getTitle();
            }

            @Override
            public String getSubtitle(GameInfo value) {
                return GameSummary.listSubtitle(value, translationSystem);
            }
        });

        getGameInfos().subscribeSelection((widget, item) -> {
            load.setEnabled(item != null);
            delete.setEnabled(item != null);
            duplicate.setEnabled(item != null);
            updateDescription(item);
            updateDetails(item);
        });

        getGameInfos().subscribe((widget, item) -> loadGame(item));

        load.subscribe(e -> {
            final GameInfo gameInfo = getGameInfos().getSelection();
            if (gameInfo != null) {
                loadGame(gameInfo);
            }
        });

        delete.subscribe(e -> {
            TwoButtonPopup confirmationPopup = getManager().pushScreen(TwoButtonPopup.ASSET_URI,
                    TwoButtonPopup.class);
            confirmationPopup.setMessage(
                    translationSystem.translate("${engine:menu#remove-confirmation-popup-title}"),
                    translationSystem.translate("${engine:menu#remove-confirmation-popup-message}"));
            confirmationPopup.setLeftButton(translationSystem.translate("${engine:menu#dialog-yes}"),
                    this::removeSelectedGame);
            confirmationPopup.setRightButton(translationSystem.translate("${engine:menu#dialog-no}"), () -> {
            });
        });

        duplicate.subscribe(e -> duplicateSelectedGame());

        final NewGameScreen newGameScreen = getManager().createScreen(NewGameScreen.ASSET_URI, NewGameScreen.class);
        create.subscribe(e -> {
            newGameScreen.setUniverseWrapper(universeWrapper);
            triggerForwardAnimation(newGameScreen);
        });

        close.subscribe(e -> triggerBackAnimation());
    }

    private void updateDetails(GameInfo gameInfo) {
        if (gameInfo == null) {
            detailTitle.setText(translationSystem.translate("${engine:menu#no-saved-games}"));
            detailLastPlayed.setText("");
            detailPlaytime.setText("");
            detailMode.setText("");
            return;
        }
        detailTitle.setText(gameInfo.getManifest().getTitle());
        detailLastPlayed.setText(GameSummary.lastPlayed(gameInfo, translationSystem));
        detailPlaytime.setText(GameSummary.playtime(gameInfo));
        detailMode.setText(GameSummary.mode(gameInfo, moduleManager));
    }

    private void removeSelectedGame() {
        final Path world =
                PathManager.getInstance().getSavePath(getGameInfos().getSelection().getManifest().getTitle());
        remove(getGameInfos(), world, REMOVE_STRING);
        refreshGameInfoList(GameProvider.getSavedGames());
    }

    /**
     * Copie la sauvegarde entiere sous un nom libre, puis reecrit le titre du manifeste — sans quoi
     * la copie porterait le nom de l'originale dans la liste et ecraserait son dossier au chargement.
     */
    private void duplicateSelectedGame() {
        final GameInfo source = getGameInfos().getSelection();
        if (source == null) {
            return;
        }
        final String newTitle = GameProvider.getNextGameName(source.getManifest().getTitle());
        final Path target = PathManager.getInstance().getSavePath(newTitle);
        try {
            copyRecursively(source.getSavePath(), target);
            GameManifest manifest = GameManifest.load(target.resolve(GameManifest.DEFAULT_FILE_NAME));
            manifest.setTitle(newTitle);
            GameManifest.save(target.resolve(GameManifest.DEFAULT_FILE_NAME), manifest);
            refreshGameInfoList(GameProvider.getSavedGames());
        } catch (IOException e) {
            logger.error("Failed to duplicate saved game", e);
            getManager().pushScreen(MessagePopup.ASSET_URI, MessagePopup.class)
                    .setMessage("Error Duplicating Game", e.getMessage());
        }
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            // Trie par profondeur : un repertoire est cree avant ce qu'il contient.
            List<Path> entries = walk.sorted(Comparator.comparingInt(Path::getNameCount)).toList();
            for (Path entry : entries) {
                Path destination = target.resolve(source.relativize(entry).toString());
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(entry, destination);
                }
            }
        }
    }

    @Override
    public void onOpened() {
        super.onOpened();

        if (!isValidScreen()) {
            final MessagePopup popup = getManager().createScreen(MessagePopup.ASSET_URI, MessagePopup.class);
            popup.setMessage(translationSystem.translate("${engine:menu#game-details-errors-message-title}"),
                    translationSystem.translate("${engine:menu#game-details-errors-message-body}"));
            popup.subscribeButton(e -> triggerBackAnimation());
            getManager().pushScreen(popup);
            setEnabled(false);
            return;
        }

        if (GameProvider.isSavesFolderEmpty()) {
            final NewGameScreen newGameScreen = getManager().createScreen(NewGameScreen.ASSET_URI,
                    NewGameScreen.class);
            newGameScreen.setUniverseWrapper(universeWrapper);
            triggerForwardAnimation(newGameScreen);
        }

        if (isLoadingAsServer()
                && super.playerConfig.playerName.getDefaultValue().equals(super.playerConfig.playerName.get())) {
            getManager().pushScreen(EnterUsernamePopup.ASSET_URI, EnterUsernamePopup.class);
        }

        refreshGameInfoList(GameProvider.getSavedGames());
    }

    private void loadGame(GameInfo item) {
        try {
            GameLauncher.launch(item, config, isLoadingAsServer());
        } catch (Exception e) {
            logger.error("Failed to load saved game", e);
            getManager().pushScreen(MessagePopup.ASSET_URI, MessagePopup.class).setMessage("Error Loading Game",
                    e.getMessage());
        }
    }

    @Override
    protected void initWidgets() {
        super.initWidgets();
        load = find("load", UIButton.class);
        delete = find("delete", UIButton.class);
        duplicate = find("duplicate", UIButton.class);
        close = find("close", UIButton.class);
        create = find("create", UIButton.class);
        gameTypeTitle = find("gameTypeTitle", UILabel.class);
        detailTitle = find("detailTitle", UILabel.class);
        detailLastPlayed = find("detailLastPlayed", UILabel.class);
        detailPlaytime = find("detailPlaytime", UILabel.class);
        detailMode = find("detailMode", UILabel.class);
    }

    public boolean isLoadingAsServer() {
        return universeWrapper != null && universeWrapper.getLoadingAsServer();
    }

    public void setUniverseWrapper(UniverseWrapper wrapper) {
        this.universeWrapper = wrapper;
    }

    @Override
    protected boolean isValidScreen() {
        if (Stream.of(load, delete, duplicate, close, create, gameTypeTitle,
                        detailTitle, detailLastPlayed, detailPlaytime, detailMode)
                .anyMatch(Objects::isNull) || !super.isValidScreen()) {
            logger.error("Can't initialize screen correctly. At least one widget was missed!");
            return false;
        }
        return true;
    }
}
