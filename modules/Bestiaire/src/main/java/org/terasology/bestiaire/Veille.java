// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.joml.Vector3f;
import org.terasology.engine.entitySystem.entity.EntityRef;

import java.util.List;

/**
 * What every creature needs to know about everything else, looked up once instead of once each.
 * <p>
 * <strong>It is a repair before it is a feature.</strong> {@code getEntitiesWith} is not an index: it streams
 * the whole entity pool and filters, so asking it for the characters costs a walk over every entity in the
 * world. {@link HuntAuthoritySystem} did that once per predator per sniff, and kept a handful of players out
 * of thousands of visits. Herds, packs and fear would each have added another such walk, per creature, per
 * frame. One sweep, shared, turns all of them into a distance compare against a list of one.
 * <p>
 * The sweep also asks {@link BeforeHuntedEvent} <em>once</em> per character rather than once per creature and
 * character, so a list that used to grow with the fauna now costs what the players cost.
 */
public interface Veille {

    /**
     * The characters a creature may notice, as of the last sweep.
     * <p>
     * Filtered already: alive, located, and not excused by {@link BeforeHuntedEvent}. A creature that wants to
     * know whether something is still worth chasing asks whether it is in here, and asks nothing else.
     */
    List<Presence> presences();

    /** Whether this entity is one of the {@link #presences()}, which is the whole test for "may I notice it". */
    boolean remarquable(EntityRef cible);

    /**
     * Where every living character stands, noticed or not.
     * <p>
     * Deliberately not the same list: a builder in creative mode is invisible to a wolf, but the world must
     * still keep its fauna loaded around them. Measuring "how far is the nearest player" against the filtered
     * list would empty the countryside the moment somebody switched to creative.
     */
    List<Vector3f> joueurs();

    /** Where a band's creatures stand, on average, or {@code null} when nobody wears that band. */
    Vector3f centre(long bande);

    /**
     * One character, as a creature sees it.
     * <p>
     * The position is a copy: the sweep hands the same object to every reader in the frame, and a reader that
     * wrote to it would move the player for all the others.
     */
    final class Presence {

        private final EntityRef personnage;
        private final Vector3f position;
        private final boolean discret;

        Presence(EntityRef personnage, Vector3f position, boolean discret) {
            this.personnage = personnage;
            this.position = position;
            this.discret = discret;
        }

        public EntityRef personnage() {
            return personnage;
        }

        public Vector3f position() {
            return position;
        }

        /** Whether it is crouching, which is how one gets near a deer. */
        public boolean discret() {
            return discret;
        }
    }
}
