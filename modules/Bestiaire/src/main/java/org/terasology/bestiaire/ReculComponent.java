// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.engine.network.Replicate;
import org.terasology.gestalt.entitysystem.component.Component;

/**
 * A creature in the air, going backwards, with no say in the matter.
 * <p>
 * Its presence is the whole protocol: while it is there the body belongs to Bullet, and every system that
 * walks a creature stands aside. It carries a clock rather than a test on the body's speed — a beast shoved
 * into a wall stops dead on the first frame, and a settle condition read off the velocity would end the
 * shove before the blow had been seen.
 */
public class ReculComponent implements Component<ReculComponent> {

    /** Seconds of flight left before the body is taken back. */
    @Replicate
    public float reste;

    @Override
    public void copyFrom(ReculComponent other) {
        this.reste = other.reste;
    }
}
