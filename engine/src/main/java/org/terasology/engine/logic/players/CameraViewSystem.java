// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.logic.players;

import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterMode;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.input.binds.general.ToggleCameraViewButton;
import org.terasology.engine.logic.console.commandSystem.annotations.Command;
import org.terasology.engine.logic.permission.PermissionManager;
import org.terasology.engine.logic.players.event.CameraViewModeChangedEvent;
import org.terasology.engine.network.ClientComponent;
import org.terasology.engine.registry.In;
import org.terasology.engine.registry.Share;
import org.terasology.gestalt.entitysystem.event.ReceiveEvent;

/**
 * Holds which point of view the local player's camera renders from, and cycles it on the "toggle camera view" bind (F5
 * by default).
 * <p>
 * The state is deliberately runtime-only and never written to the config: every session starts in {@link
 * CameraViewMode#FIRST_PERSON}.
 * <p>
 * Readers: {@link LocalPlayerSystem} moves the render camera and {@link FirstPersonClientSystem} hides the held item,
 * both by polling {@link #getViewMode()} every frame. Anything that has to be built or torn down on the switch listens
 * for {@link CameraViewModeChangedEvent} instead.
 */
@RegisterSystem(RegisterMode.CLIENT)
@Share(CameraViewSystem.class)
public class CameraViewSystem extends BaseComponentSystem {

    @In
    private LocalPlayer localPlayer;

    private CameraViewMode viewMode = CameraViewMode.FIRST_PERSON;

    public CameraViewMode getViewMode() {
        return viewMode;
    }

    public void setViewMode(CameraViewMode mode) {
        if (mode == viewMode) {
            return;
        }
        CameraViewMode oldMode = viewMode;
        viewMode = mode;
        if (localPlayer != null && localPlayer.getClientEntity().exists()) {
            localPlayer.getClientEntity().send(new CameraViewModeChangedEvent(oldMode, viewMode));
        }
    }

    @ReceiveEvent(components = ClientComponent.class)
    public void onToggleCameraView(ToggleCameraViewButton event, EntityRef entity) {
        if (event.isDown()) {
            setViewMode(viewMode.next());
            event.consume();
        }
    }

    @Command(shortDescription = "Cycle first person / third person behind / third person in front",
            requiredPermission = PermissionManager.NO_PERMISSION)
    public String cycleCameraView() {
        setViewMode(viewMode.next());
        return "Camera view: " + viewMode;
    }
}
