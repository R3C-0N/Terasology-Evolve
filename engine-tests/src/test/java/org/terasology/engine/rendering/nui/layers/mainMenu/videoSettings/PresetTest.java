// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu.videoSettings;

import org.junit.jupiter.api.Test;
import org.terasology.engine.config.RenderingConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PresetTest {

    @Test
    void eachProfileIsRecognisedOnceApplied() {
        for (Preset preset : Preset.values()) {
            if (preset != Preset.CUSTOM) {
                assertEquals(preset, Preset.find(applied(preset)), preset.name());
            }
        }
    }

    @Test
    void changingACostlySettingMakesTheProfileCustom() {
        RenderingConfig config = applied(Preset.MEDIUM);
        config.setSsao(true);
        assertEquals(Preset.CUSTOM, Preset.find(config));
    }

    @Test
    void comfortAndPacingSettingsKeepTheProfile() {
        RenderingConfig config = applied(Preset.HIGH);
        config.setCameraBobbing(false);
        config.setUiScale(150);
        config.setVSync(true);
        config.setFrameLimit(144);
        config.setChunkThreads(2);
        assertEquals(Preset.HIGH, Preset.find(config));
    }

    @Test
    void customChangesNothing() {
        RenderingConfig config = applied(Preset.LOW);
        Preset.CUSTOM.apply(config);
        assertEquals(Preset.LOW, Preset.find(config));
    }

    private static RenderingConfig applied(Preset preset) {
        RenderingConfig config = new RenderingConfig();
        preset.apply(config);
        return config;
    }
}
