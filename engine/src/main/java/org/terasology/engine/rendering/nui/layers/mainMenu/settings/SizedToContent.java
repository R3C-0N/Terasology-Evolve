// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu.settings;

import org.joml.Vector2i;
import org.terasology.nui.Canvas;
import org.terasology.nui.CoreWidget;
import org.terasology.nui.UIWidget;

import java.util.Collections;
import java.util.Iterator;

/**
 * Gives a row layout the whole size of a widget, its skin's margins and fixed sizes included.
 * <p>
 * A row cell sized to its content measures the widget's content alone, and within a slice of the row: a button got the
 * width of a fraction of its text, then lost its margins inside it and wrapped its label letter by letter. Measured
 * through the canvas, the style is counted; a fitted widget is measured on one line, a filling one within its cell.
 */
public class SizedToContent extends CoreWidget {
    private final UIWidget content;
    private final boolean wrapWithinCell;

    /**
     * @param wrapWithinCell true to measure the widget within the width the layout offers, false to measure it on one line
     */
    public SizedToContent(UIWidget content, boolean wrapWithinCell) {
        this.content = content;
        this.wrapWithinCell = wrapWithinCell;
    }

    @Override
    public void onDraw(Canvas canvas) {
        canvas.drawWidget(content);
    }

    @Override
    public Vector2i getPreferredContentSize(Canvas canvas, Vector2i sizeHint) {
        return wrapWithinCell ? canvas.calculateRestrictedSize(content, sizeHint) : canvas.calculatePreferredSize(content);
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        content.update(delta);
    }

    @Override
    public Iterator<UIWidget> iterator() {
        return Collections.singletonList(content).iterator();
    }
}
