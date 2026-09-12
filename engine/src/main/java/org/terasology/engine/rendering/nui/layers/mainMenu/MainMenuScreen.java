// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.rendering.nui.layers.mainMenu;

import org.terasology.engine.core.GameEngine;
import org.terasology.engine.core.NonNativeJVMDetector;
import org.terasology.engine.i18n.TranslationSystem;
import org.terasology.engine.identity.storageServiceClient.StorageServiceWorker;
import org.terasology.engine.identity.storageServiceClient.StorageServiceWorkerStatus;
import org.terasology.engine.registry.In;
import org.terasology.engine.rendering.nui.CoreScreenLayer;
import org.terasology.engine.rendering.nui.animation.MenuAnimationSystems;
import org.terasology.engine.rendering.nui.layers.mainMenu.settings.SettingsMenuScreen;
import org.terasology.engine.version.TerasologyVersion;
import org.terasology.nui.WidgetUtil;
import org.terasology.nui.widgets.UILabel;

/**
 * Le menu principal : quatre entrees, et rien d'autre.
 *
 * <p>« Extras » a quitte ce menu — ses ecrans restent joignables en jeu, par le menu de pause.
 * « Heberger » et « Rejoindre » ont fusionne en un « Multijoueur » a deux onglets, si bien que
 * l'hebergement ne passe plus par la liste des parties : ce menu ne pose donc plus
 * {@code loadingAsServer}.
 */
public class MainMenuScreen extends CoreScreenLayer {

    @In
    private GameEngine engine;

    @In
    private StorageServiceWorker storageService;

    @In
    private TranslationSystem translationSystem;

    @Override
    public void initialise() {

        setAnimationSystem(MenuAnimationSystems.createDefaultSwipeAnimation());

        UILabel versionLabel = find("version", UILabel.class);
        versionLabel.setText(TerasologyVersion.getInstance().getHumanVersion());

        UILabel jvmWarningLabel = find("nonNativeJvmWarning", UILabel.class);
        jvmWarningLabel.setVisible(NonNativeJVMDetector.JVM_ARCH_IS_NONNATIVE);

        SelectGameScreen selectScreen = getManager().createScreen(SelectGameScreen.ASSET_URI, SelectGameScreen.class);
        UniverseWrapper universeWrapper = new UniverseWrapper();

        WidgetUtil.trySubscribe(this, "singleplayer", button -> {
            universeWrapper.setLoadingAsServer(false);
            selectScreen.setUniverseWrapper(universeWrapper);
            triggerForwardAnimation(selectScreen);
        });
        WidgetUtil.trySubscribe(this, "multiplayer", button -> openMultiplayer());
        WidgetUtil.trySubscribe(this, "settings", button -> triggerForwardAnimation(SettingsMenuScreen.ASSET_URI));
        WidgetUtil.trySubscribe(this, "exit", button -> engine.shutdown());
    }

    /**
     * Le service de stockage d'identites peut etre en train d'ecrire quand on rejoint un serveur :
     * on previent avant d'y aller, comme le faisait l'ancien bouton « Rejoindre ».
     */
    private void openMultiplayer() {
        if (storageService.getStatus() == StorageServiceWorkerStatus.WORKING) {
            ConfirmPopup confirmPopup = getManager().pushScreen(ConfirmPopup.ASSET_URI, ConfirmPopup.class);
            confirmPopup.setMessage(translationSystem.translate("${engine:menu#warning}"),
                    translationSystem.translate("${engine:menu#storage-service-working}"));
            confirmPopup.setOkHandler(() -> triggerForwardAnimation(JoinGameScreen.ASSET_URI));
        } else {
            triggerForwardAnimation(JoinGameScreen.ASSET_URI);
        }
    }

    @Override
    public void onOpened() {
        super.onOpened();
        getAnimationSystem().skip();
    }

    @Override
    protected boolean isEscapeToCloseAllowed() {
        return false;
    }

    @Override
    public boolean isLowerLayerVisible() {
        return false;
    }
}
