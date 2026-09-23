// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.gestalt.entitysystem.component.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A mouthful of grass, and what enough of them makes of the creature.
 * <p>
 * The shorn mouflon eats and grows its fleece back; the woolly one and the cow eat and stay themselves. One
 * component, and {@code becomes} is the only difference. That is also how the shearing this design still owes
 * will close its loop: shear, graze, wool, shear.
 * <p>
 * <strong>Grazing takes the block.</strong> Grass becomes dirt, and the dirt is put back later by a marker
 * laid down at the same moment — see {@link RegrowthComponent}, which is a marker rather than a sweep because
 * the cost of a marker is the number of mouthfuls actually eaten, and the cost of a sweep is the size of the
 * world. The arithmetic that keeps this from turning a meadow into a desert is in
 * {@link GrazeAuthoritySystem}.
 */
public class GrazeComponent implements Component<GrazeComponent> {

    /** What counts as a mouthful, by block URI. */
    public List<String> eats = new ArrayList<>(List.of("CoreAssets:Grass"));

    /** What is left where it ate. */
    public String leaves = "CoreAssets:Dirt";

    /** Tufts standing on the block go with it, or they would be left hanging on nothing. */
    public List<String> clears = new ArrayList<>(List.of(
            "CoreAssets:TallGrass1", "CoreAssets:TallGrass2", "CoreAssets:TallGrass3"));

    /** Seconds between two mouthfuls. */
    public float period = 45f;

    /** How many mouthfuls before it turns into {@link #becomes}. 0 means it only ever eats. */
    public int meals;

    /** The species it turns into, by bestiary name. Empty means none. */
    public String becomes = "";

    /** Seconds before the block it ate comes back. */
    public float regrow = 1200f;

    /** Seconds to the next mouthful, and how many it has had. Both persisted: the player watches them. */
    public float clock;
    public int eaten;

    @Override
    public void copyFrom(GrazeComponent other) {
        this.eats = new ArrayList<>(other.eats);
        this.leaves = other.leaves;
        this.clears = new ArrayList<>(other.clears);
        this.period = other.period;
        this.meals = other.meals;
        this.becomes = other.becomes;
        this.regrow = other.regrow;
        this.clock = other.clock;
        this.eaten = other.eaten;
    }
}
