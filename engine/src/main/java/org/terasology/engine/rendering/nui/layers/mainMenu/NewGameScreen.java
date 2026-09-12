// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu;

import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.config.Config;
import org.terasology.engine.config.ModuleConfig;
import org.terasology.engine.core.GameEngine;
import org.terasology.engine.core.SimpleUri;
import org.terasology.engine.core.modes.StateLoading;
import org.terasology.engine.core.module.ModuleManager;
import org.terasology.engine.core.module.StandardModuleExtension;
import org.terasology.engine.game.GameManifest;
import org.terasology.engine.i18n.TranslationSystem;
import org.terasology.engine.network.NetworkMode;
import org.terasology.engine.registry.In;
import org.terasology.engine.rendering.nui.CoreScreenLayer;
import org.terasology.engine.rendering.nui.animation.MenuAnimationSystems;
import org.terasology.engine.rendering.nui.layers.mainMenu.advancedGameSetupScreen.AdvancedGameSetupScreen;
import org.terasology.engine.rendering.nui.layers.mainMenu.savedGames.GameProvider;
import org.terasology.engine.utilities.random.FastRandom;
import org.terasology.engine.world.generator.internal.WorldGeneratorInfo;
import org.terasology.engine.world.generator.internal.WorldGeneratorManager;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.module.Module;
import org.terasology.gestalt.module.dependencyresolution.DependencyResolver;
import org.terasology.gestalt.naming.Name;
import org.terasology.input.Keyboard;
import org.terasology.nui.Color;
import org.terasology.nui.WidgetUtil;
import org.terasology.nui.events.NUIKeyEvent;
import org.terasology.nui.widgets.UIList;
import org.terasology.nui.widgets.UIText;

import java.util.Comparator;
import java.util.List;

/**
 * Creer une partie : un nom, un mode, une graine. Rien d'autre au premier plan.
 *
 * <p>Le choix des modules et celui du generateur de monde restent accessibles, mais replies
 * derriere deux tiroirs « Options avancees » : ils poussent les ecrans existants
 * ({@link AdvancedGameSetupScreen}, {@link UniverseSetupScreen}) au lieu de trainer dans le
 * chemin du joueur ordinaire.
 *
 * <p>La graine remonte ici depuis l'ecran avance : elle alimente
 * {@link UniverseWrapper#setSeed}, que {@link GameManifestProvider} lit quand aucun generateur
 * n'a ete choisi a la main.
 */
public class NewGameScreen extends CoreScreenLayer {

    public static final ResourceUrn ASSET_URI = new ResourceUrn("engine:newGameScreen");

    private static final Logger logger = LoggerFactory.getLogger(NewGameScreen.class);
    private static final String DEFAULT_GAME_TEMPLATE_NAME = "JoshariasSurvival";
    private static final Color INVALID_MODULE_COLOR = new Color(0xC13B27FF);
    private static final int DESCRIPTION_LIMIT = 96;

    @In
    private ModuleManager moduleManager;

    @In
    private Config config;

    @In
    private WorldGeneratorManager worldGeneratorManager;

    @In
    private GameEngine gameEngine;

    @In
    private TranslationSystem translationSystem;

    private UniverseWrapper universeWrapper;
    private UIList<Module> gameplay;
    private UIText gameName;
    private UIText seed;

    @Override
    public void initialise() {

        setAnimationSystem(MenuAnimationSystems.createDefaultSwipeAnimation());

        gameName = find("gameName", UIText.class);
        setGameName(gameName);

        seed = find("worldSeed", UIText.class);

        gameplay = find("gameplay", UIList.class);
        gameplay.setList(getGameplayModules());
        gameplay.setItemRenderer(new TwoLineItemRenderer<Module>() {
            @Override
            public String getTitle(Module value) {
                return value.getMetadata().getDisplayName().value();
            }

            @Override
            public String getSubtitle(Module value) {
                String description = value.getMetadata().getDescription().value();
                return description.length() > DESCRIPTION_LIMIT
                        ? description.substring(0, DESCRIPTION_LIMIT) + "…"
                        : description;
            }

            @Override
            public Color getTitleColor(Module value) {
                return validateModuleDependencies(value.getId()) ? super.getTitleColor(value) : INVALID_MODULE_COLOR;
            }
        });
        gameplay.subscribeSelection((widget, module) -> {
            if (module != null) {
                setSelectedGameplayModule(module);
            }
        });

        WidgetUtil.trySubscribe(this, "rollSeed", button -> seed.setText(createRandomSeed()));

        AdvancedGameSetupScreen advancedSetupGameScreen =
                getManager().createScreen(AdvancedGameSetupScreen.ASSET_URI, AdvancedGameSetupScreen.class);
        WidgetUtil.trySubscribe(this, "advancedModules", button -> {
            captureFields();
            advancedSetupGameScreen.setEnvironment(universeWrapper);
            triggerForwardAnimation(advancedSetupGameScreen);
        });

        WidgetUtil.trySubscribe(this, "advancedUniverse", button -> {
            captureFields();
            UniverseSetupScreen universeSetupScreen =
                    getManager().createScreen(UniverseSetupScreen.ASSET_URI, UniverseSetupScreen.class);
            universeSetupScreen.setEnvironment(universeWrapper);
            triggerForwardAnimation(universeSetupScreen);
        });

        WidgetUtil.trySubscribe(this, "play", button -> {
            captureFields();
            if (gameplay.getList().isEmpty()) {
                logger.error("No gameplay modules present");
                getManager().pushScreen(MessagePopup.ASSET_URI, MessagePopup.class)
                        .setMessage("Error", "Can't create new game without modules!");
                return;
            }
            GameManifest gameManifest = GameManifestProvider.createGameManifest(universeWrapper, moduleManager, config);
            if (gameManifest != null) {
                gameEngine.changeState(new StateLoading(gameManifest,
                        isLoadingAsServer() ? NetworkMode.DEDICATED_SERVER : NetworkMode.NONE));
            } else {
                getManager().createScreen(MessagePopup.ASSET_URI, MessagePopup.class)
                        .setMessage("Error", "Can't create new game!");
            }
        });

        WidgetUtil.trySubscribe(this, "close", button -> back());
    }

    /**
     * Une graine vide donne un monde de graine vide, pas un monde aleatoire : on tire ici ce que
     * le joueur n'a pas saisi, plutot que de le laisser partir avec une chaine vide.
     */
    private void captureFields() {
        universeWrapper.setGameName(GameProvider.getNextGameName(gameName.getText()));
        String typed = seed.getText();
        if (typed == null || typed.trim().isEmpty()) {
            typed = createRandomSeed();
            seed.setText(typed);
        }
        universeWrapper.setSeed(typed);
    }

    private void back() {
        if (GameProvider.isSavesFolderEmpty()) {
            // La liste des parties a ete sautee a l'aller : on ne peut pas y retomber au retour.
            getManager().pushScreen("engine:mainMenuScreen");
        } else {
            triggerBackAnimation();
        }
    }

    private static String createRandomSeed() {
        return new FastRandom().nextString(32);
    }

    /**
     * Sets the game names based on the game number of the last saved game
     * @param nameField The {@link UIText} in which the name will be displayed.
     */
    private void setGameName(UIText nameField) {
        if (nameField != null) {
            nameField.setText(GameProvider.getNextGameName());
        }
    }

    private List<Module> getGameplayModules() {
        List<Module> gameplayModules = Lists.newArrayList();
        for (Name moduleId : moduleManager.getRegistry().getModuleIds()) {
            Module latestVersion = moduleManager.getRegistry().getLatestModuleVersion(moduleId);
            if (StandardModuleExtension.isGameplayModule(latestVersion)) {
                gameplayModules.add(latestVersion);
            }
        }
        gameplayModules.sort(Comparator.comparing(o -> o.getMetadata().getDisplayName().value()));

        return gameplayModules;
    }

    private boolean validateModuleDependencies(Name moduleName) {
        DependencyResolver resolver = new DependencyResolver(moduleManager.getRegistry());
        return resolver.resolve(moduleName).isSuccess();
    }

    private void setSelectedGameplayModule(Module module) {
        ModuleConfig moduleConfig = config.getDefaultModSelection();
        if (moduleConfig.getDefaultGameplayModuleName().equals(module.getId().toString())) {
            // same as before -> we're done
            return;
        }

        moduleConfig.setDefaultGameplayModuleName(module.getId().toString());
        moduleConfig.clear();
        moduleConfig.addModule(module.getId());

        // Set the default generator of the selected gameplay module
        setDefaultGeneratorOfGameplayModule(module);

        config.save();
    }

    // Sets the default generator of the passed in gameplay module. Make sure it's already selected.
    private void setDefaultGeneratorOfGameplayModule(Module module) {
        SimpleUri defaultWorldGenerator = StandardModuleExtension.getDefaultWorldGenerator(module);
        if (defaultWorldGenerator != null) {
            for (WorldGeneratorInfo worldGenInfo : worldGeneratorManager.getWorldGenerators()) {
                if (worldGenInfo.getUri().equals(defaultWorldGenerator)) {
                    config.getWorldGeneration().setDefaultGenerator(worldGenInfo.getUri());
                }
            }
        }

        config.save();
    }

    @Override
    public void onOpened() {
        super.onOpened();

        setGameName(gameName);
        if (seed != null && seed.getText().isEmpty()) {
            seed.setText(universeWrapper != null && !universeWrapper.getSeed().isEmpty()
                    ? universeWrapper.getSeed() : "");
        }

        String configDefaultModuleName = config.getDefaultModSelection().getDefaultGameplayModuleName();
        String useThisModuleName;

        // Get the default gameplay module from the config if it exists. This is likely to have a user triggered
        // selection. Otherwise, default to DEFAULT_GAME_TEMPLATE_NAME.
        if ("".equalsIgnoreCase(configDefaultModuleName) || DEFAULT_GAME_TEMPLATE_NAME.equalsIgnoreCase(configDefaultModuleName)) {
            useThisModuleName = DEFAULT_GAME_TEMPLATE_NAME;
        } else {
            useThisModuleName = configDefaultModuleName;
        }

        Module defaultGameplayModule = moduleManager.getRegistry().getLatestModuleVersion(new Name(useThisModuleName));
        if (defaultGameplayModule != null && gameplay.getList().contains(defaultGameplayModule)) {
            gameplay.setSelection(defaultGameplayModule);
            setDefaultGeneratorOfGameplayModule(defaultGameplayModule);
        } else if (!gameplay.getList().isEmpty()) {
            gameplay.select(0);
        }
    }

    public boolean isLoadingAsServer() {
        return universeWrapper != null && universeWrapper.getLoadingAsServer();
    }

    public void setUniverseWrapper(UniverseWrapper wrapper) {
        this.universeWrapper = wrapper;
    }

    @Override
    public boolean onKeyEvent(NUIKeyEvent event) {
        if (event.isDown() && event.getKey() == Keyboard.Key.ESCAPE && GameProvider.isSavesFolderEmpty()) {
            // skip selectGameScreen and get back directly to main screen
            getManager().pushScreen("engine:mainMenuScreen");
            return true;
        }
        return super.onKeyEvent(event);
    }

    @Override
    public boolean isLowerLayerVisible() {
        return false;
    }
}
