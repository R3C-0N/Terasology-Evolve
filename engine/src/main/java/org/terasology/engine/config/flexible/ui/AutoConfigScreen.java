// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.config.flexible.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.config.flexible.AutoConfig;
import org.terasology.engine.config.flexible.AutoConfigManager;
import org.terasology.engine.core.module.ModuleManager;
import org.terasology.engine.registry.In;
import org.terasology.engine.rendering.nui.layers.mainMenu.settings.SettingsTab;
import org.terasology.engine.rendering.nui.layers.mainMenu.settings.SettingsTabScreen;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.assets.management.AssetManager;
import org.terasology.nui.UIWidget;
import org.terasology.nui.databinding.Binding;
import org.terasology.nui.databinding.DefaultBinding;
import org.terasology.nui.layouts.ColumnLayout;
import org.terasology.nui.widgets.types.TypeWidgetLibrary;

import java.util.Collections;
import java.util.Optional;

/**
 * The Modules tab of the options: the settings each loaded module declares, one framed panel per configuration.
 */
public class AutoConfigScreen extends SettingsTabScreen {
    public static final Logger logger = LoggerFactory.getLogger(AutoConfigScreen.class);
    public static final ResourceUrn ASSET_URI = SettingsTab.MODULES.getAssetUri();

    @In
    private TypeWidgetLibrary typeWidgetLibrary;
    @In
    private ModuleManager moduleManager;
    @In
    private AssetManager assetManager;
    @In
    private AutoConfigManager configManager;

    @Override
    protected SettingsTab getTab() {
        return SettingsTab.MODULES;
    }

    @Override
    public void initialise() {
        initialiseTabs();
        ColumnLayout sections = find("sections", ColumnLayout.class);
        assert sections != null;
        int configurations = 0;
        for (AutoConfig config : configManager.getLoadedConfigs()) {
            Binding<AutoConfig> configBinding = new DefaultBinding<>(config);

            Optional<UIWidget> widget = typeWidgetLibrary.getWidget(configBinding, AutoConfig.class);
            if (widget.isPresent()) {
                sections.addWidget(rows().section(config.getName(), null, Collections.singletonList(widget.get())));
                configurations++;
            } else {
                logger.warn("Cannot create widget for config: {}", config.getId()); //NOPMD
            }
        }
        if (configurations == 0) {
            sections.addWidget(rows().section("${engine:menu#opt-tab-modules}", "${engine:menu#opt-modules-empty}",
                    Collections.emptyList()));
        }
        setSettingCount(configurations);
    }
}
