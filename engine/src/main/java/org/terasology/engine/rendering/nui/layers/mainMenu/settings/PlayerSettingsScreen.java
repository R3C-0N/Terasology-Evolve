// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu.settings;

import org.terasology.engine.config.PlayerConfig;
import org.terasology.engine.config.SystemConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.i18n.TranslationSystem;
import org.terasology.engine.identity.storageServiceClient.StorageServiceWorker;
import org.terasology.engine.identity.storageServiceClient.StorageServiceWorkerStatus;
import org.terasology.engine.registry.In;
import org.terasology.engine.rendering.nui.layers.mainMenu.StorageServiceLoginPopup;
import org.terasology.engine.rendering.nui.layers.mainMenu.ThreeButtonPopup;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.nui.layouts.ColumnLayout;
import org.terasology.nui.layouts.RowLayout;
import org.terasology.nui.widgets.UIButton;
import org.terasology.nui.widgets.UILabel;
import org.terasology.nui.widgets.UISpace;

import java.util.Arrays;

import static org.terasology.engine.identity.storageServiceClient.StatusMessageTranslator.getLocalizedButtonMessage;
import static org.terasology.engine.identity.storageServiceClient.StatusMessageTranslator.getLocalizedStatusMessage;

/**
 * The Player tab of the options: multiplayer identities and the storage service that syncs them.
 */
public class PlayerSettingsScreen extends SettingsTabScreen {

    public static final ResourceUrn ASSET_URI = SettingsTab.PLAYER.getAssetUri();

    @In
    private Context context;
    @In
    private PlayerConfig config;
    @In
    private SystemConfig systemConfig;
    @In
    private TranslationSystem translationSystem;
    @In
    private StorageServiceWorker storageService;

    private UILabel storageServiceStatus;
    private UIButton storageServiceAction;

    private StorageServiceWorkerStatus storageServiceWorkerStatus;

    @Override
    protected SettingsTab getTab() {
        return SettingsTab.PLAYER;
    }

    @Override
    public void initialise() {
        initialiseTabs();
        SettingsRows rows = rows();

        IdentityIOHelper identityIOHelper = new IdentityIOHelper(context);
        RowLayout identities = rows.line(10);
        SettingsRows.fit(identities, rows.button("${engine:menu#player-settings-identities-import}", "choice-off",
                identityIOHelper::importIdentities));
        SettingsRows.fit(identities, rows.button("${engine:menu#player-settings-identities-export}", "choice-off",
                identityIOHelper::exportIdentities));
        SettingsRows.fill(identities, new UISpace());

        storageServiceStatus = rows.label("", "setting-state");
        storageServiceAction = rows.button("", "choice-off", this::onStorageServiceAction);
        RowLayout storage = rows.line(10);
        SettingsRows.fill(storage, storageServiceStatus);
        SettingsRows.fit(storage, storageServiceAction);

        RowLayout identityRow = rows.row("${engine:menu#opt-identities}", "${engine:menu#opt-identities-help}", identities);
        RowLayout storageRow = rows.row("${engine:menu#storage-service}", "${engine:menu#opt-storage-help}", storage);
        // Both stay disabled, as they were on the screen this tab replaces.
        identityRow.setEnabled(false);
        storageRow.setEnabled(false);

        ColumnLayout sections = find("sections", ColumnLayout.class);
        if (sections != null) {
            sections.addWidget(rows.section("${engine:menu#opt-section-identity}", "${engine:menu#opt-section-identity-note}",
                    Arrays.asList(identityRow, storageRow)));
        }
        setSettingCount(2);
        updateStorageServiceStatus();
    }

    private void onStorageServiceAction() {
        if (storageService.getStatus() == StorageServiceWorkerStatus.LOGGED_IN) {
            ThreeButtonPopup logoutPopup = getManager().pushScreen(ThreeButtonPopup.ASSET_URI, ThreeButtonPopup.class);
            logoutPopup.setMessage(translationSystem.translate("${engine:menu#storage-service-log-out}"),
                    translationSystem.translate("${engine:menu#storage-service-log-out-popup}"));
            logoutPopup.setLeftButton(translationSystem.translate("${engine:menu#dialog-yes}"), () -> storageService.logout(true));
            logoutPopup.setCenterButton(translationSystem.translate("${engine:menu#dialog-no}"), () -> storageService.logout(false));
            logoutPopup.setRightButton(translationSystem.translate("${engine:menu#dialog-cancel}"), () -> { });
        } else if (storageService.getStatus() == StorageServiceWorkerStatus.LOGGED_OUT) {
            getManager().pushScreen(StorageServiceLoginPopup.ASSET_URI, StorageServiceLoginPopup.class);
        }
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        if (storageService.getStatus() != storageServiceWorkerStatus) {
            updateStorageServiceStatus();
        }
    }

    private void updateStorageServiceStatus() {
        StorageServiceWorkerStatus stat = storageService.getStatus();
        storageServiceStatus.setText(getLocalizedStatusMessage(stat, translationSystem, storageService.getLoginName()));
        storageServiceAction.setText(getLocalizedButtonMessage(stat, translationSystem));
        storageServiceAction.setVisible(stat.isButtonEnabled());
        storageServiceWorkerStatus = stat;
    }
}
