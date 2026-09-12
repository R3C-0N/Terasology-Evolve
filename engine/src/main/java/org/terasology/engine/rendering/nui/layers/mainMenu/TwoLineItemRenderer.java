// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu;

import org.joml.Vector2i;
import org.terasology.engine.utilities.Assets;
import org.terasology.joml.geom.Rectanglei;
import org.terasology.nui.Canvas;
import org.terasology.nui.Color;
import org.terasology.nui.HorizontalAlign;
import org.terasology.nui.VerticalAlign;
import org.terasology.nui.asset.font.Font;
import org.terasology.nui.itemRendering.AbstractItemRenderer;

/**
 * Rend une entree de liste sur deux niveaux : un nom en fonte display, une ligne d'appoint en
 * dessous. C'est la forme qu'ont toutes les listes de la refonte — parties, serveurs, modes de jeu.
 *
 * <p>Un {@code UIList} rend {@code toString()} par defaut, sur une seule ligne et d'une seule
 * couleur : les deux niveaux ne s'obtiennent que par un {@code ItemRenderer}.
 */
public abstract class TwoLineItemRenderer<T> extends AbstractItemRenderer<T> {

    protected static final Color TITLE_COLOR = new Color(0xFFF3DCFF);
    protected static final Color SUBTITLE_COLOR = new Color(0xB49E7CFF);
    protected static final int GAP = 2;

    private static final String TITLE_FONT = "engine:Uncial-Item";
    private static final String SUBTITLE_FONT = "engine:Grenze-Small";

    public abstract String getTitle(T value);

    public abstract String getSubtitle(T value);

    /** Couleur du nom. Surchargee quand l'entree porte un etat — un module qui ne se resout pas. */
    public Color getTitleColor(T value) {
        return TITLE_COLOR;
    }

    @Override
    public void draw(T value, Canvas canvas) {
        Rectanglei region = canvas.getRegion();
        Font title = font(TITLE_FONT);
        Font subtitle = font(SUBTITLE_FONT);
        int titleHeight = title.getLineHeight();

        canvas.drawTextRaw(getTitle(value), title, getTitleColor(value),
                new Rectanglei(region.minX, region.minY, region.maxX, region.minY + titleHeight),
                HorizontalAlign.LEFT, VerticalAlign.TOP);
        canvas.drawTextRaw(getSubtitle(value), subtitle, SUBTITLE_COLOR,
                new Rectanglei(region.minX, region.minY + titleHeight + GAP, region.maxX, region.maxY),
                HorizontalAlign.LEFT, VerticalAlign.TOP);
    }

    @Override
    public Vector2i getPreferredSize(T value, Canvas canvas) {
        Font title = font(TITLE_FONT);
        Font subtitle = font(SUBTITLE_FONT);
        int width = Math.max(title.getWidth(getTitle(value)), subtitle.getWidth(getSubtitle(value)));
        return new Vector2i(width, title.getLineHeight() + GAP + subtitle.getLineHeight());
    }

    protected static Font font(String urn) {
        return Assets.getFont(urn)
                .orElseGet(() -> Assets.getFont("engine:Grenze-Body").orElseThrow(
                        () -> new IllegalStateException("Aucune fonte d'interface disponible : " + urn)));
    }
}
