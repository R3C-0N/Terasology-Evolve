// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.joml.Vector3f;
import org.terasology.engine.core.Time;
import org.terasology.engine.entitySystem.entity.EntityManager;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterMode;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.logic.characters.AliveCharacterComponent;
import org.terasology.engine.logic.characters.CharacterComponent;
import org.terasology.engine.logic.characters.CharacterMovementComponent;
import org.terasology.engine.logic.characters.MovementMode;
import org.terasology.engine.logic.location.LocationComponent;
import org.terasology.engine.registry.In;
import org.terasology.engine.registry.Share;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one sweep of the world that every creature reads.
 * <p>
 * It has no update loop on purpose. The order in which {@code UpdateSubscriberSystem}s run is not promised,
 * so a system that refreshed the snapshot on its own tick would hand a stale or a fresh answer depending on
 * registration order — a difference nothing would ever print. Instead the first reader of a frame that finds
 * the snapshot older than {@link #SOUFFLE} rebuilds it, and everyone after that reads the same thing.
 * <p>
 * The clock is the engine's game time rather than an accumulated delta, because two readers each adding their
 * own delta would count the same frame twice.
 */
@RegisterSystem(RegisterMode.AUTHORITY)
@Share(Veille.class)
public class VeilleSystem extends BaseComponentSystem implements Veille {

    /**
     * Milliseconds between two sweeps.
     * <p>
     * The same four hundredths of a second {@link HuntAuthoritySystem} already waits between sniffs. At a
     * sprint that is 1.8 blocks of lag, which is under a stride and well under any radius that reads the
     * snapshot.
     */
    private static final long SOUFFLE = 400L;

    @In
    private EntityManager entityManager;

    @In
    private Time time;

    private final List<Presence> presences = new ArrayList<>();
    private final List<Vector3f> joueurs = new ArrayList<>();
    private final Map<Long, Vector3f> centres = new HashMap<>();
    /**
     * When the last sweep ran, in game milliseconds.
     * <p>
     * Not {@code Long.MIN_VALUE}: the staleness test is a subtraction, and subtracting the smallest long from
     * a small positive one overflows to a negative, which reads as "fresh". The snapshot then stays empty for
     * the life of the process, and every creature simply behaves as though the world were deserted — no
     * exception, no log line, nothing to see. One sweep behind the start is enough and cannot overflow.
     */
    private long dernier = -SOUFFLE;

    @Override
    public List<Presence> presences() {
        balayer();
        return Collections.unmodifiableList(presences);
    }

    @Override
    public boolean remarquable(EntityRef cible) {
        if (!cible.exists()) {
            return false;
        }
        for (Presence presence : presences()) {
            if (presence.personnage().equals(cible)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<Vector3f> joueurs() {
        balayer();
        return Collections.unmodifiableList(joueurs);
    }

    @Override
    public Vector3f centre(long bande) {
        balayer();
        return centres.get(bande);
    }

    private void balayer() {
        long maintenant = time.getGameTimeInMs();
        if (maintenant - dernier < SOUFFLE) {
            return;
        }
        dernier = maintenant;
        recenserPersonnages();
        recenserBandes();
    }

    /**
     * Every living character that has not been excused from being noticed.
     * <p>
     * {@link BeforeHuntedEvent} is asked here, once per character, and its answer serves the whole frame. It
     * used to be asked once per predator per sniff; with a herd of a dozen and a pack of four that was a
     * dozen and four identical questions with one identical answer.
     */
    private void recenserPersonnages() {
        presences.clear();
        joueurs.clear();
        for (EntityRef personnage : entityManager.getEntitiesWith(CharacterComponent.class,
                AliveCharacterComponent.class, LocationComponent.class)) {
            LocationComponent location = personnage.getComponent(LocationComponent.class);
            if (location == null) {
                continue;
            }
            Vector3f position = location.getWorldPosition(new Vector3f());
            if (!position.isFinite()) {
                continue;
            }
            joueurs.add(position);
            BeforeHuntedEvent question = new BeforeHuntedEvent();
            personnage.send(question);
            if (question.isConsumed()) {
                continue;
            }
            CharacterMovementComponent mouvement = personnage.getComponent(CharacterMovementComponent.class);
            boolean discret = mouvement != null && mouvement.mode == MovementMode.CROUCHING;
            presences.add(new Presence(personnage, position, discret));
        }
    }

    /** The centre of each band, from one pass over the creatures that wear one. */
    private void recenserBandes() {
        centres.clear();
        Map<Long, Integer> comptes = new HashMap<>();
        for (EntityRef creature : entityManager.getEntitiesWith(HomeComponent.class, LocationComponent.class)) {
            HomeComponent foyer = creature.getComponent(HomeComponent.class);
            LocationComponent location = creature.getComponent(LocationComponent.class);
            if (foyer == null || location == null || foyer.bande == 0L) {
                continue;
            }
            Vector3f position = location.getWorldPosition(new Vector3f());
            if (!position.isFinite()) {
                continue;
            }
            centres.computeIfAbsent(foyer.bande, b -> new Vector3f()).add(position);
            comptes.merge(foyer.bande, 1, Integer::sum);
        }
        for (Map.Entry<Long, Vector3f> entree : centres.entrySet()) {
            entree.getValue().div(comptes.get(entree.getKey()));
        }
    }
}
