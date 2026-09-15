// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu.settings;

import org.terasology.nui.Colorc;

/**
 * How much a setting weighs on the time a frame takes, shown as up to three lit pips.
 */
public enum Impact {
    /** No measurable cost. */
    NONE(0),
    /** Under a millisecond. */
    LOW(1),
    /** One to three milliseconds. */
    MODERATE(2),
    /** More than four milliseconds. */
    MAJOR(3);

    private final int level;

    Impact(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }

    /** The colour of pip {@code index}: lit in the impact's tone up to its level, unlit beyond. */
    public Colorc pip(int index) {
        if (index >= level) {
            return SettingsPalette.OFF;
        }
        switch (this) {
            case LOW:
                return SettingsPalette.SUCCESS;
            case MODERATE:
                return SettingsPalette.ACCENT;
            default:
                return SettingsPalette.DANGER;
        }
    }

    public String getLabel() {
        return "${engine:menu#opt-impact-" + level + "}";
    }

    public String getLegend() {
        return "${engine:menu#opt-legend-" + level + "}";
    }

    /**
     * The skin family of the impact's name. Red holds its contrast as a fill, not as text, so the major impact is written
     * in plain ink and only its pips are red.
     */
    public String getFamily() {
        return "impact-" + level;
    }
}
