// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * What the world is doing with its wildlife, so a command can say it out loud.
 * <p>
 * A species that never appears is otherwise indistinguishable from a species that appears rarely, and the
 * difference is a typo. {@link #journal()} is the answer: every refused spot says why it was refused.
 */
public interface Faune {

    /** Tries {@code essais} spots now and says how many creatures were born. */
    int peupler(int essais);

    /** The last attempts, newest first, each with its verdict. */
    List<String> journal();

    /** How many wild creatures live in the world, all species together. */
    int total();

    /** The ceiling {@link #total()} is measured against. */
    int plafond();

    /** How many wild creatures of each species live within {@code rayon} of a point. */
    Map<String, Integer> recensement(Vector3f autour, float rayon);

    /** The four radii derived from the view distance: ring near, ring far, forgetting, crowd. */
    float[] rayons();
}
