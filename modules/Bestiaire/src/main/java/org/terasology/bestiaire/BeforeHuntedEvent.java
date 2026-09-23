// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.terasology.engine.entitySystem.event.AbstractConsumableEvent;

/**
 * Asked of a character before a hostile creature settles on it. Consume it, and the creature looks elsewhere.
 * <p>
 * A wolf leaves a builder alone, and this is how it is told — a question, not a rule written into the wolf.
 * Creative mode is the only answer today ({@code CreativeAuthoritySystem}), and it is the reason the question
 * exists at all: the mode lives in the gameplay module, which depends on this one, so the bestiary cannot
 * read it and must not try. Asking instead of reading also leaves the door open — a hiding place, a scent, a
 * truce — without the bestiary having to know about any of them.
 * <p>
 * Consuming it stops the hunt, not the bite: a creature already chasing drops its prey within the moment it
 * takes to sniff round again, and a creature that somehow reaches an unhuntable character would still find
 * its blow stopped by whatever made it unhuntable. Creative mode consumes {@code BeforeDamagedEvent} too.
 */
public class BeforeHuntedEvent extends AbstractConsumableEvent {
}
