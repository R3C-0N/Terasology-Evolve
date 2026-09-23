// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.joml.Vector3f;
import org.terasology.gestalt.entitysystem.component.Component;

/**
 * A point the creature does not stray far from.
 * <p>
 * A herd, a territory and a den are the same thing seen three ways, so they are one component. A herd's point
 * drifts after the group it belongs to; a territory's point never moves; a den is a territory whose point is
 * written once a day, when daylight sends a night hunter looking for cover. Nothing here knows which of the
 * three it is, and that is the point — the prefab does.
 * <p>
 * <strong>It is a component and not a line in a system's map because the player can see it.</strong> A bear
 * whose land moved every time the world reloaded would not be territorial. The rule the module follows: what
 * a player can watch persisting lives on the entity, what they cannot — a turn timer, a sniff clock — stays
 * in the system.
 * <p>
 * <strong>The point is never probed.</strong> It is steered towards and measured against, never read as a
 * block and never arrived at by teleport. That is what makes "the anchor ended up inside rock" and "the
 * anchor is in an unloaded chunk" impossible rather than merely unlikely.
 */
public class HomeComponent implements Component<HomeComponent> {

    /** Where it wants to be, in world coordinates. */
    public Vector3f point = new Vector3f();

    /**
     * Whether that point has been chosen yet.
     * <p>
     * A prefab cannot carry a place, so a creature laid down by a totem or by the console would otherwise
     * start out wanting to be at the world origin — walking there for ever, and a territorial one refusing to
     * notice anybody who was not standing on it. The flag is how a point of nobody's choosing is told from a
     * point at coordinate zero, which is a real place somebody may well be standing on.
     */
    public boolean pose;

    /**
     * Which group's centre the point follows. 0 means it follows nobody and stays where it was put.
     * <p>
     * A band is handed out by whoever creates the group and is never looked up in reverse: the centre is
     * computed by {@link Veille} in one linear pass, which is exact, where a grid of buckets would split a
     * herd whenever it straddled an edge.
     */
    public long bande;

    /** How fast the point eases towards the band's centre, in blocks per second. 0 pins it. */
    public float suit;

    /**
     * Inside {@code franc} the point pulls not at all; at {@code laisse} it pulls at full strength.
     * <p>
     * The gap is what makes a herd a herd rather than a heap: with no slack every member would steer at the
     * average of the others, and since creatures do not collide they would end up standing in one another.
     */
    public float franc = 8f;
    public float laisse = 20f;

    /**
     * How many degrees a strolling heading may be bent homewards. 0 turns the whole mechanism off.
     * <p>
     * A bend, never a heading of its own: the animal still wanders, it merely wanders back. And the bend is
     * applied when a spell is chosen, never every frame — see {@link WanderAuthoritySystem}, where exactly one
     * writer of the heading per frame is the rule that keeps a blocked animal able to turn round.
     */
    public float tire;

    @Override
    public void copyFrom(HomeComponent other) {
        this.point = new Vector3f(other.point);
        this.pose = other.pose;
        this.bande = other.bande;
        this.suit = other.suit;
        this.franc = other.franc;
        this.laisse = other.laisse;
        this.tire = other.tire;
    }
}
