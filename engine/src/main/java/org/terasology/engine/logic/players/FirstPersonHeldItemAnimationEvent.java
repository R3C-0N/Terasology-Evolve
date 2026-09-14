// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.logic.players;

import org.joml.Vector3f;
import org.terasology.gestalt.entitysystem.event.Event;

/**
 * Sent to the local character every frame, before the first person held item is placed, so that a module can move
 * it differently from the default use swing — scraping, sawing, anything that is not a hit.
 * <p>
 * It arrives carrying the default swing; a handler that has nothing to say leaves it alone.
 */
public class FirstPersonHeldItemAnimationEvent implements Event {
    private final long timeSinceLastUse;
    private float pitch;
    private float yaw;
    private final Vector3f offset;

    public FirstPersonHeldItemAnimationEvent(long timeSinceLastUse, float pitch, float yaw, Vector3f offset) {
        this.timeSinceLastUse = timeSinceLastUse;
        this.pitch = pitch;
        this.yaw = yaw;
        this.offset = offset;
    }

    public long getTimeSinceLastUse() {
        return timeSinceLastUse;
    }

    /** Degrees added to the mount point's pitch. */
    public float getPitch() {
        return pitch;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    /** Degrees added to the mount point's yaw. */
    public float getYaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    /** Added to the mount point's translation; mutable in place. */
    public Vector3f getOffset() {
        return offset;
    }
}
