// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.gestalt.entitysystem.component.Component;

/**
 * The whole temperament of a hostile creature: it picks a prey, it runs it down, and it bites.
 * <p>
 * The design gives the bestiary two temperaments and no more, so this is the other half of
 * {@link WanderComponent} — and a hostile animal wears both. Strolling is what it does with nobody in sight;
 * everything here takes over the moment someone is. What separates a wolf from a boar will be these numbers,
 * exactly as the cow and the rabbit are told apart by the strolling ones.
 * <p>
 * Nothing is replicated. A chase is decided, run and resolved on the server, and the client learns of it the
 * only way it needs to: the creature is coming, and its legs are going fast — which
 * {@link GaitClientSystem} works out from the positions it already receives.
 * <p>
 * Everything is in blocks, blocks per second, seconds, and degrees.
 */
public class PredatorComponent implements Component<PredatorComponent> {

    /**
     * How far it notices a prey, and how far that prey must get to be dropped.
     * <p>
     * The two are deliberately different, and the gap is what lets anyone escape. A single distance would
     * make the creature blink on and off at the edge of its own sight: one step out and it forgets, one step
     * in and it charges again. Given room, {@link #giveUp} is also the promise that running away eventually
     * works.
     */
    public float sight = 16f;
    public float giveUp = 24f;

    /** How high above or below a prey may stand and still be seen at all. */
    public float reachUp = 5f;

    /**
     * Pace once it has a prey.
     * <p>
     * A shade <em>under</em> a sprinting player, and that is the whole balance of the thing. Faster, and the
     * only answers left are to fight or to die, since {@link #giveUp} could never be reached. Slower than a
     * walk, and it would never be a threat at all. Just under a sprint makes running away work and makes it
     * cost: the gap closes the moment you stop, look round, or meet a slope.
     */
    public float chaseSpeed = 4.2f;

    /** How fast the body swings round to the prey, in degrees per second. */
    public float turnRate = 400f;

    /** How close it must be to bite, from body centre to body centre. */
    public float reach = 1.9f;

    /**
     * What one bite takes, and how long before the next.
     * <p>
     * Five points a second against a hundred, which leaves about twenty seconds of standing still — long
     * enough to be a fight rather than an accident, short enough that ignoring it is not an option.
     */
    public int bite = 6;
    public float cadence = 1.2f;

    /**
     * Highest step it will climb and deepest drop it will take while hunting.
     * <p>
     * Bolder than the strolling limits of {@link WanderComponent} on purpose: an animal picking its way
     * across a meadow has no reason to jump off a ledge, and one running a prey down has every reason.
     */
    public float stepUp = 1.05f;
    public float dropMax = 4f;

    @Override
    public void copyFrom(PredatorComponent other) {
        this.sight = other.sight;
        this.giveUp = other.giveUp;
        this.reachUp = other.reachUp;
        this.chaseSpeed = other.chaseSpeed;
        this.turnRate = other.turnRate;
        this.reach = other.reach;
        this.bite = other.bite;
        this.cadence = other.cadence;
        this.stepUp = other.stepUp;
        this.dropMax = other.dropMax;
    }
}
