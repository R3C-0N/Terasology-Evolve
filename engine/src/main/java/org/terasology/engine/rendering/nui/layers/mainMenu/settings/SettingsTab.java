// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu.settings;

import org.terasology.gestalt.assets.ResourceUrn;

/**
 * The tabs of the options screen, in the order they appear. Each tab is its own screen; the tab bar swaps one for another.
 */
public enum SettingsTab {
    VIDEO("tabVideo", "${engine:menu#opt-tab-video}", "engine:VideoMenuScreen"),
    AUDIO("tabAudio", "${engine:menu#opt-tab-audio}", "engine:AudioMenuScreen"),
    INPUT("tabInput", "${engine:menu#opt-tab-input}", "engine:inputSettingsScreen"),
    PLAYER("tabPlayer", "${engine:menu#opt-tab-player}", "engine:PlayerMenuScreen"),
    MODULES("tabModules", "${engine:menu#opt-tab-modules}", "engine:autoConfigScreen");

    private final String buttonId;
    private final String label;
    private final ResourceUrn assetUri;

    SettingsTab(String buttonId, String label, String assetUri) {
        this.buttonId = buttonId;
        this.label = label;
        this.assetUri = new ResourceUrn(assetUri);
    }

    public String getButtonId() {
        return buttonId;
    }

    public String getLabel() {
        return label;
    }

    public ResourceUrn getAssetUri() {
        return assetUri;
    }
}
