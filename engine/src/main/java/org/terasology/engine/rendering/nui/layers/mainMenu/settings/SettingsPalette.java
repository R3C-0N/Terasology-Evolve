// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu.settings;

import org.terasology.nui.Canvas;
import org.terasology.nui.Color;
import org.terasology.nui.Colorc;
import org.terasology.nui.util.RectUtility;

/**
 * The flat colours of the HeroCraft design system that the settings screens paint themselves, at the dawn palette.
 * <p>
 * Skins carry the textures and the fonts; pips, gauges and bars have no texture and draw with these instead.
 */
public final class SettingsPalette {
    /** An unlit pip: {@code --surface-inset}. */
    public static final Colorc OFF = new Color(0x3D200DFF);
    /** {@code --border-hairline}, also {@code --surface-sunken}. */
    public static final Colorc HAIRLINE = new Color(0x20120AFF);
    public static final Colorc SUCCESS = new Color(0x7CC452FF);
    public static final Colorc ACCENT = new Color(0xF5C542FF);
    public static final Colorc DANGER = new Color(0xC13B27FF);
    public static final Colorc TEXT_SECONDARY = new Color(0xF2E2C0FF);
    public static final Colorc TEXT_MUTED = new Color(0xB49E7CFF);
    /** {@code --fitting-hi}: the iron of a marker. */
    public static final Colorc FITTING_HIGHLIGHT = new Color(0xE3E9ECFF);
    /** The one-texel light and dark edges of {@code --bevel-raised}. */
    public static final Colorc BEVEL_LIGHT = new Color(255, 240, 210, 107);
    public static final Colorc BEVEL_DARK = new Color(0, 0, 0, 107);

    private SettingsPalette() {
    }

    static void fill(Canvas canvas, int x, int y, int width, int height, Colorc color) {
        if (width > 0 && height > 0) {
            canvas.drawFilledRectangle(RectUtility.createFromMinAndSize(x, y, width, height), color);
        }
    }

    static void outline(Canvas canvas, int x, int y, int width, int height, Colorc color) {
        fill(canvas, x, y, width, 1, color);
        fill(canvas, x, y + height - 1, width, 1, color);
        fill(canvas, x, y, 1, height, color);
        fill(canvas, x + width - 1, y, 1, height, color);
    }
}
