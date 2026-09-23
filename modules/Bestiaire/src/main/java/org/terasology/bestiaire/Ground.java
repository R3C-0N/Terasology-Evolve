// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.joml.Vector3f;
import org.terasology.engine.world.WorldProvider;
import org.terasology.engine.world.block.Block;

/**
 * Where a creature's feet belong, above a given column.
 * <p>
 * Falling and walking both need this answer and they have to get the <em>same</em> one: a creature that finds
 * the floor one way while moving and another way while settling would jitter between the two readings forever.
 */
final class Ground {

    /** How far a creature caught inside the ground is pushed back out, in blocks. */
    private static final int EXTRACTION = 8;

    /** How far down the probe looks before giving up and letting the fall continue. */
    private static final int PORTEE = 65;

    private Ground() {
    }

    /**
     * The height the feet belong at, or {@code NaN} when the world cannot answer.
     * <p>
     * Two cases, and forgetting the second is what buried the first mannequins up to the neck: the feet may be
     * <em>inside</em> the ground — spawned there, or built around since — and looking only downwards then finds
     * a floor below the one the creature is standing in, and lets it sink one block further. So the block
     * holding the feet is read first, and while it is solid the answer climbs.
     */
    static float under(WorldProvider world, float x, float feet, float z) {
        Vector3f sonde = new Vector3f(x, 0, z);
        int contenant = (int) Math.floor(feet + 0.5f);

        Boolean plein = solide(world, sonde, contenant);
        if (plein == null) {
            return Float.NaN;
        }
        if (plein) {
            // Enfoui : on remonte jusqu'au-dessus de la derniere case pleine.
            for (int y = contenant + 1; y <= contenant + EXTRACTION; y++) {
                Boolean encore = solide(world, sonde, y);
                if (encore == null) {
                    return Float.NaN;
                }
                if (!encore) {
                    return y - 0.5f;
                }
            }
            return contenant + EXTRACTION + 0.5f;
        }

        int limite = contenant - PORTEE;
        for (int y = contenant - 1; y > limite; y--) {
            Boolean dessous = solide(world, sonde, y);
            if (dessous == null) {
                return Float.NaN;
            }
            if (dessous) {
                return y + 0.5f;
            }
        }
        // Rien jusqu'en bas : on continue de tomber, et l'image suivante regardera plus bas.
        return limite + 0.5f;
    }

    /**
     * How far above the feet the nearest ceiling is, or {@code NaN} when there is none within {@code portee}.
     * <p>
     * The mirror of {@link #under}, and it exists for one creature: the cave lizard is laid down against a
     * roof rather than on a floor. The answer is the underside of the first solid cell — where a creature
     * hanging from it has its back — so it is a height in the same units as {@link #under}, and the two
     * subtract to give the headroom of a gallery.
     */
    static float above(WorldProvider world, float x, float feet, float z, int portee) {
        Vector3f sonde = new Vector3f(x, 0, z);
        int depart = (int) Math.floor(feet + 0.5f);
        for (int y = depart; y <= depart + portee; y++) {
            Boolean plein = solide(world, sonde, y);
            if (plein == null) {
                return Float.NaN;
            }
            if (plein) {
                return y - 0.5f;
            }
        }
        return Float.NaN;
    }

    /** Whether a creature could stand in this cell — {@code false} when the world has not arrived either. */
    static boolean libre(WorldProvider world, float x, float y, float z) {
        Boolean plein = solide(world, new Vector3f(x, 0, z), (int) Math.floor(y + 0.5f));
        return plein != null && !plein;
    }

    /** {@code true} when the cell holds a liquid — water counts as penetrable, so it is asked separately. */
    static boolean liquide(WorldProvider world, float x, float y, float z) {
        Vector3f sonde = new Vector3f(x, y, z);
        if (!world.isBlockRelevant(sonde)) {
            return false;
        }
        Block bloc = world.getBlock(sonde);
        return bloc != null && bloc.isLiquid();
    }

    /** {@code null} when no loaded chunk covers the cell — the world has not arrived yet. */
    private static Boolean solide(WorldProvider world, Vector3f sonde, int y) {
        sonde.y = y;
        if (!world.isBlockRelevant(sonde)) {
            return null;
        }
        Block bloc = world.getBlock(sonde);
        return bloc != null && !bloc.isPenetrable();
    }
}
