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
     * Whether it hunts nobody until somebody hits it.
     * <p>
     * The bear is slow to anger and the treant stands still until a tree of its grove comes down: two
     * creatures the design describes by what does <em>not</em> set them off. Both already have the whole
     * temperament — reach, bite, the lot — and what they lack is the first step, so this takes away the sweep
     * of the surroundings and leaves the blow. Struck, such a creature is exactly as dangerous as its numbers
     * say, and it is the only one you choose to fight.
     */
    public boolean onlyWhenStruck;

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
     * How far it looks for a packmate of its own species, in blocks. 0 means it hunts alone.
     * <p>
     * A packmate with no prey adopts the one its neighbour has, which is the whole of "they hunt together":
     * one wolf finding you commits the pack, and the pack is whoever happens to be within this radius. There
     * is no roster and no leader, so nothing has to be kept, replicated or repaired when one of them dies.
     */
    public float meute;

    /**
     * The circle each member wants a station on, in blocks. 0 sends everyone straight at the prey.
     * <p>
     * <strong>Encircling is conditional, and it has to be</strong>, or it takes the pack's teeth out: a wolf
     * running to a station is not closing, so it never reaches {@link #reach} and never bites. And at 4.2
     * against a sprint of 4.5 no station on the far side is reachable at all. So the ring is taken only
     * against a prey that has slowed down, the nearest wolf never takes one, and a station is abandoned after
     * a few seconds whatever happens.
     */
    public float cercle;

    /**
     * How far from its home point it takes an interest at all, in blocks. 0 means the whole world.
     * <p>
     * The test is against the <em>point</em>, not against the beast, and that is the difference between
     * territorial and short-sighted: a bear does not care that you are near it, it cares that you are on its
     * land. It is also the whole of the boar's charge — its slow turn was already written, this is what
     * starts it.
     */
    public float garde;

    /** How far from that point it will follow a prey before breaking off and going home. 0 means forever. */
    public float portee;

    /**
     * The cone, in degrees, inside which being looked at stops it. 0 means being watched changes nothing.
     * <p>
     * The three beasts of the deeps differ from the nine above by <em>what they perceive</em>, not by what
     * they do, and this is the first of the three dials that say so. The giant centipede is still by default
     * — {@code Wander.speed} of zero, which is the whole of "it stays particularly still" — and what starts
     * it is a character near enough and looking elsewhere. Look back at it and it stops, mid-stride.
     * <p>
     * It is tested against the gaze and not against the body, and the cone is wide: a player sweeping a
     * gallery with a torch should freeze it, and a player walking past staring at their feet should not.
     */
    public float guet;

    /**
     * How far it hears, in blocks. Above 0 it is <em>blind</em>: presence is nothing to it, only noise.
     * <p>
     * Both halves matter. A character is heard when it is within this radius <em>and</em> within its own
     * {@link Veille.Presence#bruit()} — so walking gives itself away at nine blocks, a pickaxe blow at
     * twenty-four, and crouching at none at all. And what the creature charges is the <em>place the noise
     * came from</em>, not the character: go quiet and step aside, and it arrives where you were.
     */
    public float ouie;

    /**
     * How near, horizontally, a prey must pass under a clinging creature before it lets go. 0 never lets go.
     * <p>
     * The ambush is not a lunge and not an animation: {@link AccrocheComponent} comes off and gravity does
     * the rest, from whatever height the ceiling happened to be. The radius is small on purpose — the lizard
     * is placed on a roof and what the design asks of it is that you walk <em>under</em> it, not near it.
     */
    public float embuscade;

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
        this.onlyWhenStruck = other.onlyWhenStruck;
        this.chaseSpeed = other.chaseSpeed;
        this.turnRate = other.turnRate;
        this.reach = other.reach;
        this.bite = other.bite;
        this.cadence = other.cadence;
        this.meute = other.meute;
        this.cercle = other.cercle;
        this.garde = other.garde;
        this.portee = other.portee;
        this.guet = other.guet;
        this.ouie = other.ouie;
        this.embuscade = other.embuscade;
        this.stepUp = other.stepUp;
        this.dropMax = other.dropMax;
    }
}
