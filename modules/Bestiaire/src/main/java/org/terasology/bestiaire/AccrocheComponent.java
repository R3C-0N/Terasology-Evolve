// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.gestalt.entitysystem.component.Component;

/**
 * The creature is hanging under a ceiling, and nothing may move it.
 * <p>
 * It is a marker and not a number because it is not a degree of anything: a beast is either holding on or it
 * is falling. Three systems read it and all three do the same thing with it — {@link GravityAuthoritySystem}
 * does not pull it down, {@link WanderAuthoritySystem} does not walk it about, and
 * {@link HuntAuthoritySystem} takes it off, which <em>is</em> the ambush. Letting go needs no code of its
 * own: gravity has been waiting all along.
 * <p>
 * <strong>It is never put back on.</strong> A lizard that dropped has dropped, and it hunts on the floor like
 * anything else. Climbing back would need a wall to climb, which is the pathfinding this module does not
 * have (D21) — and a beast that teleported back onto its ceiling would read as a bug, rightly.
 */
public class AccrocheComponent implements Component<AccrocheComponent> {

    @Override
    public void copyFrom(AccrocheComponent other) {
    }
}
