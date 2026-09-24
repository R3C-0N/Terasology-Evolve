// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.gestalt.entitysystem.component.Component;

/**
 * Bolted to the ground: a blow never moves it.
 * <p>
 * The training dummy is the one creature that must not be knocked back, and the reason is what it is for —
 * a target that slides away after the first swing cannot be used to measure a second. It is a marker and not
 * a test on the species: the day a second fixture arrives, a scarecrow or a pell, it says so in its prefab
 * rather than adding a name to a list buried in {@link ReculAuthoritySystem}.
 */
public class ScelleComponent implements Component<ScelleComponent> {

    @Override
    public void copyFrom(ScelleComponent other) {
    }
}
