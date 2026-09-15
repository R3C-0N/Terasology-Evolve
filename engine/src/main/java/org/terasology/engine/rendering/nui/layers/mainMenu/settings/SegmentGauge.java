// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu.settings;

import org.joml.Vector2i;
import org.terasology.nui.Canvas;
import org.terasology.nui.Colorc;
import org.terasology.nui.CoreWidget;

import java.util.function.IntFunction;

/**
 * A row of segments, each with its own colour: the three impact pips of a setting, or the strip of graphics profiles.
 * <p>
 * Colours are asked for on every frame, so the gauge follows the settings without being told they changed. A segment
 * width of zero stretches the segments over the whole width.
 */
public class SegmentGauge extends CoreWidget {
    private final int segments;
    private final int segmentWidth;
    private final int height;
    private final int gap;
    private final IntFunction<Colorc> colors;

    public SegmentGauge(int segments, int segmentWidth, int height, int gap, IntFunction<Colorc> colors) {
        this.segments = segments;
        this.segmentWidth = segmentWidth;
        this.height = height;
        this.gap = gap;
        this.colors = colors;
    }

    @Override
    public void onDraw(Canvas canvas) {
        Vector2i size = canvas.size();
        int width = segmentWidth > 0 ? segmentWidth : Math.max(1, (size.x - gap * (segments - 1)) / segments);
        int drawnHeight = Math.min(height, size.y);
        int y = (size.y - drawnHeight) / 2;
        for (int i = 0; i < segments; i++) {
            int x = i * (width + gap);
            SettingsPalette.fill(canvas, x, y, width, drawnHeight, colors.apply(i));
            SettingsPalette.outline(canvas, x, y, width, drawnHeight, SettingsPalette.HAIRLINE);
        }
    }

    @Override
    public Vector2i getPreferredContentSize(Canvas canvas, Vector2i sizeHint) {
        int width = Math.max(segmentWidth, 1);
        return new Vector2i(segments * width + gap * (segments - 1), height);
    }
}
