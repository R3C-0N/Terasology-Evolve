// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu.settings;

import org.terasology.engine.config.AudioConfig;
import org.terasology.engine.config.flexible.Setting;
import org.terasology.engine.registry.In;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.nui.UIWidget;
import org.terasology.nui.layouts.ColumnLayout;
import org.terasology.nui.widgets.UISlider;

import java.util.Arrays;
import java.util.List;

/**
 * The Sound tab of the options.
 */
public class AudioSettingsScreen extends SettingsTabScreen {

    public static final ResourceUrn ASSET_URI = SettingsTab.AUDIO.getAssetUri();

    @In
    private AudioConfig config;

    @Override
    protected SettingsTab getTab() {
        return SettingsTab.AUDIO;
    }

    @Override
    public void initialise() {
        initialiseTabs();
        SettingsRows rows = rows();

        List<UIWidget> volumes = Arrays.asList(
                rows.row("${engine:menu#sound-volume}", "${engine:menu#opt-sound-help}", volume(rows, config.soundVolume)),
                rows.row("${engine:menu#music-volume}", "${engine:menu#opt-music-help}", volume(rows, config.musicVolume)));

        ColumnLayout sections = find("sections", ColumnLayout.class);
        if (sections != null) {
            sections.addWidget(rows.section("${engine:menu#opt-section-volume}", "${engine:menu#opt-section-volume-note}", volumes));
        }
        setSettingCount(volumes.size());
    }

    /** A volume from 0 to 1, shown and set in whole percent. */
    private static UISlider volume(SettingsRows rows, Setting<Float> setting) {
        return rows.slider(0, 100, 5, SettingsRows.bindFloat(() -> setting.get() * 100f, value -> setting.set(value / 100f)),
                rows::percent);
    }
}
