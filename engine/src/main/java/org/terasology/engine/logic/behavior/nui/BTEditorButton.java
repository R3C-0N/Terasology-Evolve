// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.logic.behavior.nui;

import org.terasology.engine.input.BindButtonEvent;
import org.terasology.engine.input.DefaultBinding;
import org.terasology.input.InputType;
import org.terasology.input.Keyboard;
import org.terasology.engine.input.RegisterBindButton;

@RegisterBindButton(id = "behavior_editor", description = "${engine:menu#binding-behavior-editor}", category = "behavior")
// F5 now cycles the camera view, as it does in most voxel games. The behaviour tree editor is a development tool and
// moves to F7, the only function key still free.
@DefaultBinding(type = InputType.KEY, id = Keyboard.KeyId.F7)
public class BTEditorButton extends BindButtonEvent {
}
