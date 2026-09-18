// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.input.binds.general;

import org.terasology.engine.input.BindButtonEvent;
import org.terasology.engine.input.DefaultBinding;
import org.terasology.engine.input.RegisterBindButton;
import org.terasology.input.InputType;
import org.terasology.input.Keyboard;

@RegisterBindButton(id = "toggleCameraView", description = "${engine:menu#binding-toggle-camera-view}",
        category = "general")
@DefaultBinding(type = InputType.KEY, id = Keyboard.KeyId.F5)
public class ToggleCameraViewButton extends BindButtonEvent {
}
