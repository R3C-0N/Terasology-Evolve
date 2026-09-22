// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.subsystem.inspect;

import org.joml.Vector3i;
import org.terasology.engine.world.block.BlockRegion;

/**
 * Une requete de grille analysee : la region a lire, et l'orientation sous laquelle on la rend.
 * <p>
 * Toute l'orientation de la grille tient dans {@link #index(int, int, int)}. C'est ce qui permet a
 * {@code /slice} selon n'importe quel axe et a {@code /cube} de partager le meme echantillonneur :
 * ils ne different que par le rangement des cases, jamais par le parcours du monde.
 */
public final class GridSpec {

    /** D'ou vient le centre de la grille. Toujours reemis dans l'en-tete de la reponse. */
    public enum Anchor { PLAYER, TARGET, EXPLICIT }

    public enum Axis { X, Y, Z }

    /**
     * Une dimension de la grille : l'axe du monde qu'elle suit, la coordonnee de son indice 0, son
     * nombre de cases, et son sens. Un pas de -1 rend une dimension decroissante — c'est ainsi
     * qu'une coupe verticale se lit de haut en bas, comme on la dessine.
     */
    public static final class Dim {
        private final Axis axis;
        private final int start;
        private final int count;
        private final int step;

        public Dim(Axis axis, int start, int count, int step) {
            this.axis = axis;
            this.start = start;
            this.count = count;
            this.step = step;
        }

        public static Dim ascending(Axis axis, int from, int count) {
            return new Dim(axis, from, count, 1);
        }

        public static Dim descending(Axis axis, int from, int count) {
            return new Dim(axis, from, count, -1);
        }

        public int index(int worldCoord) {
            return (worldCoord - start) * step;
        }

        public int coordAt(int index) {
            return start + index * step;
        }

        public int min() {
            return step > 0 ? start : start - (count - 1);
        }

        public int max() {
            return step > 0 ? start + (count - 1) : start;
        }

        public Axis axis() {
            return axis;
        }

        public int count() {
            return count;
        }

        public boolean descending() {
            return step < 0;
        }
    }

    private final Vector3i anchor;
    private final Anchor anchorKind;
    private final String fallback;
    private final Dim layers;
    private final Dim rows;
    private final Dim cols;
    private final BlockRegion region;
    private final boolean wantFlow;

    public GridSpec(Vector3i anchor, Anchor anchorKind, String fallback,
                    Dim layers, Dim rows, Dim cols, boolean wantFlow) {
        this.anchor = anchor;
        this.anchorKind = anchorKind;
        this.fallback = fallback;
        this.layers = layers;
        this.rows = rows;
        this.cols = cols;
        this.wantFlow = wantFlow;
        this.region = buildRegion();
    }

    private BlockRegion buildRegion() {
        int[] min = new int[3];
        int[] max = new int[3];
        for (int i = 0; i < 3; i++) {
            min[i] = Integer.MAX_VALUE;
            max[i] = Integer.MIN_VALUE;
        }
        for (Dim dim : new Dim[]{layers, rows, cols}) {
            int a = dim.axis().ordinal();
            min[a] = Math.min(min[a], dim.min());
            max[a] = Math.max(max[a], dim.max());
        }
        return new BlockRegion(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    /**
     * Range une position du monde dans le tableau de cases, en ordre de lecture : couche, puis
     * ligne, puis colonne.
     */
    public int index(int x, int y, int z) {
        int layer = layers.index(coord(layers.axis(), x, y, z));
        int row = rows.index(coord(rows.axis(), x, y, z));
        int col = cols.index(coord(cols.axis(), x, y, z));
        return (layer * rows.count() + row) * cols.count() + col;
    }

    public static int coord(Axis axis, int x, int y, int z) {
        switch (axis) {
            case X: return x;
            case Y: return y;
            default: return z;
        }
    }

    public int cellCount() {
        return layers.count() * rows.count() * cols.count();
    }

    public BlockRegion region() {
        return region;
    }

    public Dim layers() {
        return layers;
    }

    public Dim rows() {
        return rows;
    }

    public Dim cols() {
        return cols;
    }

    public Vector3i anchor() {
        return anchor;
    }

    public Anchor anchorKind() {
        return anchorKind;
    }

    public String fallback() {
        return fallback;
    }

    public boolean wantFlow() {
        return wantFlow;
    }

    public String anchorLabel() {
        return anchorKind.name().toLowerCase(java.util.Locale.ROOT);
    }
}
