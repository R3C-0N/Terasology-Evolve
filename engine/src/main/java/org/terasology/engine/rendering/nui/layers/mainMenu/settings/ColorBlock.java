// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu.settings;

import org.joml.Vector2i;
import org.terasology.nui.Canvas;
import org.terasology.nui.Colorc;
import org.terasology.nui.CoreWidget;

import java.util.function.Supplier;

/**
 * A block of flat colour: a hairline between rows, the accent edge of a measured cost, a tone chip.
 * <p>
 * It fills whatever region its layout gives it, so a one-pixel block in a column becomes a full-width rule.
 */
public class ColorBlock extends CoreWidget {
    private final Vector2i preferredSize;
    private final Supplier<Colorc> color;
    private final boolean outlined;

    public ColorBlock(int width, int height, Supplier<Colorc> color, boolean outlined) {
        this.preferredSize = new Vector2i(width, height);
        this.color = color;
        this.outlined = outlined;
    }

    public static ColorBlock of(int width, int height, Colorc color) {
        return new ColorBlock(width, height, () -> color, false);
    }

    @Override
    public void onDraw(Canvas canvas) {
        Vector2i size = canvas.size();
        SettingsPalette.fill(canvas, 0, 0, size.x, size.y, color.get());
        if (outlined) {
            SettingsPalette.outline(canvas, 0, 0, size.x, size.y, SettingsPalette.HAIRLINE);
        }
    }

    @Override
    public Vector2i getPreferredContentSize(Canvas canvas, Vector2i sizeHint) {
        return new Vector2i(preferredSize);
    }
}
