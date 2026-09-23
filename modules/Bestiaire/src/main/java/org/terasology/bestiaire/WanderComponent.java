// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.engine.network.Replicate;
import org.terasology.gestalt.entitysystem.component.Component;

/**
 * The whole behaviour of a peaceful animal: it strolls, and it bolts when struck.
 * <p>
 * The design gives the nineteen creatures of the bestiary two temperaments and no more — hostile or not — so
 * this component is deliberately <em>not</em> the mouflon's. All six animals of the {@code paisible} world
 * wear it, and what separates a rabbit from a cow is exactly these numbers, not a second system: the cow
 * ambles at 0.8 and turns at 90 degrees a second, the rabbit changes its mind every eighth of a second while
 * fleeing. What is hostile will need something else entirely: it has a target, and a target is a different
 * problem.
 * <p>
 * Everything is in blocks per second, seconds, and degrees. {@code @Replicate} is there for the client-side
 * gait, which reads {@link #speed} to know what a normal stride looks like for this animal.
 */
public class WanderComponent implements Component<WanderComponent> {

    /** Strolling pace. */
    @Replicate
    public float speed = 1.4f;

    /** Pace once frightened — a fleeing animal has to outrun a walking player. */
    @Replicate
    public float panicSpeed = 5.5f;

    /** How long a blow keeps it running, in seconds. */
    public float panicDuration = 6f;

    /** Bounds of a standing-still spell, in seconds. */
    public float pauseMin = 1.5f;
    public float pauseMax = 5f;

    /** Bounds of a walking spell, in seconds. */
    public float walkMin = 2f;
    public float walkMax = 6f;

    /** Bounds of a panicked dash before it changes its mind again, in seconds. */
    public float dashMin = 0.25f;
    public float dashMax = 0.8f;

    /** How far a new heading may swing away from the current one, in degrees. */
    public float turnCalm = 120f;
    public float turnPanic = 180f;

    /** How fast the body swings round to the heading it picked, in degrees per second. */
    public float turnRate = 200f;
    public float turnRatePanic = 700f;

    /**
     * Highest step it will walk up, and deepest drop it will walk off, in blocks.
     * <p>
     * A step of one block plus a hair, and the hair is the point: terrain is made of whole blocks, so a limit
     * of 0.6 lets an animal climb nothing at all. It is hemmed in by the first slope it meets and shuffles
     * against it — which is exactly what the first mouflon did. A character gets over the same step by
     * jumping; a quadruped steps onto it.
     */
    public float stepUp = 1.05f;
    public float dropMax = 3f;

    @Override
    public void copyFrom(WanderComponent other) {
        this.speed = other.speed;
        this.panicSpeed = other.panicSpeed;
        this.panicDuration = other.panicDuration;
        this.pauseMin = other.pauseMin;
        this.pauseMax = other.pauseMax;
        this.walkMin = other.walkMin;
        this.walkMax = other.walkMax;
        this.dashMin = other.dashMin;
        this.dashMax = other.dashMax;
        this.turnCalm = other.turnCalm;
        this.turnPanic = other.turnPanic;
        this.turnRate = other.turnRate;
        this.turnRatePanic = other.turnRatePanic;
        this.stepUp = other.stepUp;
        this.dropMax = other.dropMax;
    }
}
