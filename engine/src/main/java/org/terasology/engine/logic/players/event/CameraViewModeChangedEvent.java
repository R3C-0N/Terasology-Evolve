// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.logic.players.event;

import org.terasology.engine.logic.players.CameraViewMode;
import org.terasology.gestalt.entitysystem.event.Event;

/**
 * Sent to the local client entity when the player switches point of view.
 * <p>
 * The camera itself does not need this event - it polls the mode every frame. It exists for everything that has to be
 * built or torn down on the switch, the local player's own body first of all.
 */
public class CameraViewModeChangedEvent implements Event {

    private final CameraViewMode oldMode;
    private final CameraViewMode newMode;

    public CameraViewModeChangedEvent(CameraViewMode oldMode, CameraViewMode newMode) {
        this.oldMode = oldMode;
        this.newMode = newMode;
    }

    public CameraViewMode getOldMode() {
        return oldMode;
    }

    public CameraViewMode getNewMode() {
        return newMode;
    }
}
