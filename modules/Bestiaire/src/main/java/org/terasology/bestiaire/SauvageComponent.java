// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.gestalt.entitysystem.component.Component;

/**
 * This one the world put here, so the world may take it back.
 * <p>
 * <strong>What the world laid down, the world picks up; what you laid down stays.</strong> A totem and the
 * {@code creature} command do not stamp this marker, so nothing ever sweeps what a player placed on purpose —
 * and nothing counts it against the population ceiling either. That is deliberate and it cuts both ways:
 * {@code creature loup 50} is still possible, and {@code destroyEntitiesUsingPrefab} is still the cure.
 * <p>
 * The marker is what makes "nothing wild is ever written to disk" a property rather than a hope. Terasology
 * serialises a creature into its chunk when the chunk unloads and restores it verbatim later, forever, and
 * {@code setPersistent(false)} does not change that — the test only looks at <em>owned</em> entities. So
 * {@link FauneAuthoritySystem} removes wild creatures by distance, and again on {@code BeforeChunkUnload} for
 * whatever the distance rule missed.
 */
public class SauvageComponent implements Component<SauvageComponent> {

    /**
     * Game time, in milliseconds, until which the sweep leaves this creature alone.
     * <p>
     * A blow pushes it a minute out. Without it: you wound a mouflon, it bolts at five and a half blocks a
     * second, and half a minute later it crosses the forgetting radius and <em>vanishes in front of you</em>,
     * mid-chase. That bug arrives within ten minutes of shipping, and it reads as the game cheating.
     */
    public long protegeJusqua;

    @Override
    public void copyFrom(SauvageComponent other) {
        this.protegeJusqua = other.protegeJusqua;
    }
}
