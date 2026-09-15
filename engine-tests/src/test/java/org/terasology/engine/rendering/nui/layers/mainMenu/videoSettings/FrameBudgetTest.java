// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu.videoSettings;

import org.junit.jupiter.api.Test;
import org.terasology.engine.config.RenderingConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameBudgetTest {
    private static final double EPSILON = 1e-9;

    @Test
    void mediumProfileAddsUpToTheReferenceFrame() {
        // Per-pixel work: 8.8 base, 1.6 local reflections, 4.2 shadows, 1.4 for six small effects = 16.0 ms.
        // World work: 10.3 base, 2.75 for 13 chunks, 1.1 for one level of detail, 0.6 for 64 billboard chunks = 14.75 ms.
        assertEquals(30.75, FrameBudget.frameMillis(applied(Preset.MEDIUM)), EPSILON);
    }

    @Test
    void profilesCostMoreFromMinimalToUltra() {
        double previous = 0;
        for (Preset preset : new Preset[]{Preset.MINIMAL, Preset.LOW, Preset.MEDIUM, Preset.HIGH, Preset.ULTRA}) {
            double millis = FrameBudget.frameMillis(applied(preset));
            assertTrue(millis > previous, preset.name());
            previous = millis;
        }
    }

    @Test
    void renderScaleOnlyShrinksPerPixelWork() {
        RenderingConfig config = applied(Preset.MEDIUM);
        config.setFboScale(50);
        assertEquals(16.0 * Math.pow(0.5, 1.1) + 14.75, FrameBudget.frameMillis(config), EPSILON);
    }

    @Test
    void frameRateIsCappedByVerticalSyncThenByTheFrameLimit() {
        RenderingConfig config = applied(Preset.MINIMAL);
        double uncapped = 1000 / FrameBudget.frameMillis(config);
        assertTrue(uncapped > 60);

        config.setVSync(true);
        assertEquals(60, FrameBudget.framesPerSecond(config), EPSILON);
        config.setVSync(false);
        config.setFrameLimit(50);
        assertEquals(50, FrameBudget.framesPerSecond(config), EPSILON);
        config.setFrameLimit(-1);
        assertEquals(uncapped, FrameBudget.framesPerSecond(config), EPSILON);
    }

    private static RenderingConfig applied(Preset preset) {
        RenderingConfig config = new RenderingConfig();
        preset.apply(config);
        return config;
    }
}
