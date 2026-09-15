// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu.settings;

import org.joml.Vector2i;
import org.terasology.nui.Canvas;
import org.terasology.nui.Colorc;
import org.terasology.nui.CoreWidget;

import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * The frame budget gauge: a sunken slot filled up to the estimated frame time, with an iron marker at the target.
 */
public class BudgetBar extends CoreWidget {
    private static final int HEIGHT = 24;
    private static final int INSET = 4;
    private static final int MARKER_WIDTH = 2;

    private final DoubleSupplier fraction;
    private final Supplier<Colorc> tone;
    private final double marker;

    /**
     * @param fraction how full the bar is, from 0 to 1; values outside are clamped
     * @param tone the colour of the fill
     * @param marker where the target marker stands, from 0 to 1
     */
    public BudgetBar(DoubleSupplier fraction, Supplier<Colorc> tone, double marker) {
        this.fraction = fraction;
        this.tone = tone;
        this.marker = marker;
    }

    @Override
    public void onDraw(Canvas canvas) {
        Vector2i size = canvas.size();
        SettingsPalette.fill(canvas, 0, 0, size.x, size.y, SettingsPalette.HAIRLINE);
        SettingsPalette.outline(canvas, 1, 1, size.x - 2, size.y - 2, SettingsPalette.OFF);

        int inner = size.x - 2 * INSET;
        int filled = (int) Math.round(inner * Math.max(0, Math.min(1, fraction.getAsDouble())));
        int fillHeight = size.y - 2 * INSET;
        SettingsPalette.fill(canvas, INSET, INSET, filled, fillHeight, tone.get());
        SettingsPalette.fill(canvas, INSET, INSET, filled, 1, SettingsPalette.BEVEL_LIGHT);
        SettingsPalette.fill(canvas, INSET, INSET + fillHeight - 1, filled, 1, SettingsPalette.BEVEL_DARK);

        int markerX = INSET + (int) Math.round(inner * marker) - MARKER_WIDTH / 2;
        SettingsPalette.fill(canvas, markerX, 0, MARKER_WIDTH, size.y, SettingsPalette.FITTING_HIGHLIGHT);
    }

    @Override
    public Vector2i getPreferredContentSize(Canvas canvas, Vector2i sizeHint) {
        return new Vector2i(Math.max(sizeHint.x, 2 * INSET), HEIGHT);
    }
}
