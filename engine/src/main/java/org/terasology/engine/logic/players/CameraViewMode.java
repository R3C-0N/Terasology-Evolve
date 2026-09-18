// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.logic.players;

/**
 * The point of view the local player's camera renders from.
 * <p>
 * The declaration order is the cycling order: the "toggle camera view" bind walks through the constants with {@link
 * #next()} and wraps back to {@link #FIRST_PERSON}.
 */
public enum CameraViewMode {
    /** At the character's eyes, looking where the character looks. */
    FIRST_PERSON,
    /** Pulled back behind the character, looking the same way it does. */
    THIRD_PERSON_BACK,
    /** Pushed forward in front of the character, looking back at it. */
    THIRD_PERSON_FRONT;

    private static final CameraViewMode[] VALUES = values();

    public CameraViewMode next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }

    public boolean isFirstPerson() {
        return this == FIRST_PERSON;
    }
}
