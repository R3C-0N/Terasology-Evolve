// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.engine.network.BroadcastEvent;
import org.terasology.engine.network.NetworkEvent;

/**
 * A creature took a hit, and every client should show how much.
 * <p>
 * {@code OnDamagedEvent} is an {@code @OwnerEvent}: it reaches the client that owns the damaged entity, which
 * for a creature is nobody. The number the player is owed therefore needs its own event, broadcast on the
 * creature — the entity carries the position already, so nothing but the amount travels.
 */
@BroadcastEvent
public class DamageNumberEvent extends NetworkEvent {

    private int amount;

    public DamageNumberEvent() {
    }

    public DamageNumberEvent(int amount) {
        this.amount = amount;
    }

    public int getAmount() {
        return amount;
    }
}
