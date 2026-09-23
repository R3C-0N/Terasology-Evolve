// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.gestalt.entitysystem.component.Component;

/**
 * A marker on an item: this blade opens a carcass.
 * <p>
 * The knives themselves live in {@code CoreSampleGameplay}, which already says what they are with
 * {@code Tool{family: "knife"}} — but that module <em>depends on</em> this one, so reading its component
 * from here would close a cycle. The marker goes the way the dependency already runs: declared here, put on
 * the eleven knife prefabs there. It is the same trade {@code CreatureComponent.pickable} makes, and it has
 * the same virtue — the gesture is decided by the object, not by a list of names kept in a system.
 * <p>
 * It carries no grade. Any knife skins anything: the grade is the tempo of digging, and a carcass is not a
 * block.
 */
public class DepeceurComponent implements Component<DepeceurComponent> {

    @Override
    public void copyFrom(DepeceurComponent other) {
    }
}
