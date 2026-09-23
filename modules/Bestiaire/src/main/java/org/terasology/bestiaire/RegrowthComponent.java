// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.joml.Vector3i;
import org.terasology.gestalt.entitysystem.component.Component;

/**
 * One eaten block, waiting to come back.
 * <p>
 * A bare entity carrying this and a location, with a delayed action on it. <strong>The state is the marker,
 * so the cost is the number of mouthfuls actually eaten and nothing else.</strong> The two alternatives are
 * both worse: a map inside the system does not survive a reload, and a sweep over dirt cells is either
 * unbounded or it turns the player's own paths green.
 * <p>
 * A marker inside an unloaded chunk simply sleeps and fires when the chunk comes back — the engine
 * de-registers delayed actions on deactivation and re-registers them on activation, so a save costs nothing.
 * The catch is that the delay runs on <em>game</em> time, not world time, so {@code setWorldTime} does not
 * advance it; that is why there is a {@code repousse} command.
 */
public class RegrowthComponent implements Component<RegrowthComponent> {

    /** The cell that was eaten. */
    public Vector3i cellule = new Vector3i();

    /** What to put back there. */
    public String rendre = "";

    /** What was left in its place — the block still has to be that, or somebody has built since. */
    public String laisse = "";

    public RegrowthComponent() {
    }

    public RegrowthComponent(Vector3i cellule, String rendre, String laisse) {
        this.cellule = new Vector3i(cellule);
        this.rendre = rendre;
        this.laisse = laisse;
    }

    @Override
    public void copyFrom(RegrowthComponent other) {
        this.cellule = new Vector3i(other.cellule);
        this.rendre = other.rendre;
        this.laisse = other.laisse;
    }
}
