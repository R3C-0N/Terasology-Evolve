// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.joml.Vector3f;
import org.terasology.engine.entitySystem.entity.EntityManager;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterMode;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.entitySystem.systems.UpdateSubscriberSystem;
import org.terasology.engine.logic.location.LocationComponent;
import org.terasology.engine.physics.components.RigidBodyComponent;
import org.terasology.engine.physics.components.shapes.BoxShapeComponent;
import org.terasology.engine.registry.In;
import org.terasology.engine.world.WorldProvider;
import org.terasology.engine.world.block.Block;

import java.util.HashMap;
import java.util.Map;

/**
 * Creatures fall.
 * <p>
 * A creature's body is <em>kinematic</em>: Bullet never moves it, which is what keeps a blow from shoving the
 * target across the ground — a target that backs away measures nothing. The price of that choice is that
 * gravity has to be given back by hand, and this is where.
 * <p>
 * Moving the {@link LocationComponent} is enough to move the collision box with it: for a kinematic body
 * Bullet asks {@code EntityMotionState.getWorldTransform} where the entity is, every step, instead of telling
 * it. Nothing has to touch the physics engine.
 * <p>
 * The fall uses the character's own numbers, copied rather than imported: {@code KinematicCharacterMover}
 * is engine internals, and a module that reaches for a class the sandbox has not opened fails at load, in
 * silence. Copied or not, they have to be the same numbers — two bodies falling at different rates in the
 * same world reads as a bug long before anyone can name it.
 */
@RegisterSystem(RegisterMode.AUTHORITY)
public class GravityAuthoritySystem extends BaseComponentSystem implements UpdateSubscriberSystem {

    /** Under this, the feet are on the ground and nothing moves. */
    private static final float EPSILON = 0.001f;

    /** The engine's own character gravity and terminal velocity, in blocks per second. */
    private static final float GRAVITE = 28.0f;
    private static final float VITESSE_MAX = 64.0f;

    /** How far a creature caught inside the ground is pushed back out, in blocks. */
    private static final int EXTRACTION = 8;

    @In
    private EntityManager entityManager;

    @In
    private WorldProvider worldProvider;

    private final Map<EntityRef, Float> vitesses = new HashMap<>();

    @Override
    public void update(float delta) {
        vitesses.keySet().removeIf(entity -> !entity.exists());

        for (EntityRef creature : entityManager.getEntitiesWith(CreatureComponent.class,
                LocationComponent.class, RigidBodyComponent.class)) {
            LocationComponent location = creature.getComponent(LocationComponent.class);
            BoxShapeComponent box = creature.getComponent(BoxShapeComponent.class);
            if (location == null) {
                continue;
            }
            float demiHauteur = box == null ? 0.5f : box.extents.y / 2f;

            Vector3f position = location.getWorldPosition(new Vector3f());
            float pieds = position.y - demiHauteur;

            // A block owns [p - 0.5, p + 0.5]. The ground may be under the feet, or around them.
            float sol = solSous(position.x, pieds, position.z);
            if (Float.isNaN(sol)) {
                // Nothing readable underneath: the chunk is not loaded. Falling here would drop the creature
                // through a world that simply has not arrived yet.
                vitesses.remove(creature);
                continue;
            }
            if (Math.abs(pieds - sol) <= EPSILON) {
                vitesses.remove(creature);
                continue;
            }

            if (sol > pieds) {
                // Remontee hors du sol : immediate, ce n'est pas une chute.
                position.y += sol - pieds;
                location.setWorldPosition(position);
                creature.saveComponent(location);
                vitesses.remove(creature);
                continue;
            }

            float vitesse = vitesses.getOrDefault(creature, 0f);
            vitesse = Math.min(VITESSE_MAX, vitesse + GRAVITE * delta);
            float chute = Math.min(vitesse * delta, pieds - sol);

            position.y -= chute;
            location.setWorldPosition(position);
            creature.saveComponent(location);

            if (chute >= pieds - sol - EPSILON) {
                vitesses.remove(creature);
            } else {
                vitesses.put(creature, vitesse);
            }
        }
    }

    /**
     * The height the feet belong at, or {@code NaN} when the world cannot answer.
     * <p>
     * Two cases, and forgetting the second is what buried the first mannequins up to the neck: the feet may be
     * <em>inside</em> the ground — spawned there, or built around since — and looking only downwards then finds
     * a floor below the one the creature is standing in, and lets it sink one block further. So the block
     * holding the feet is read first, and while it is solid the answer climbs.
     */
    private float solSous(float x, float pieds, float z) {
        Vector3f sonde = new Vector3f(x, 0, z);
        int contenant = (int) Math.floor(pieds + 0.5f);

        Boolean plein = solide(sonde, contenant);
        if (plein == null) {
            return Float.NaN;
        }
        if (plein) {
            // Enfoui : on remonte jusqu'au-dessus de la derniere case pleine.
            for (int y = contenant + 1; y <= contenant + EXTRACTION; y++) {
                Boolean encore = solide(sonde, y);
                if (encore == null) {
                    return Float.NaN;
                }
                if (!encore) {
                    return y - 0.5f;
                }
            }
            return contenant + EXTRACTION + 0.5f;
        }

        int limite = contenant - (int) Math.ceil(VITESSE_MAX) - 1;
        for (int y = contenant - 1; y > limite; y--) {
            Boolean dessous = solide(sonde, y);
            if (dessous == null) {
                return Float.NaN;
            }
            if (dessous) {
                return y + 0.5f;
            }
        }
        // Rien jusqu'en bas : on continue de tomber, et l'image suivante regardera plus bas.
        return limite + 0.5f;
    }

    /** {@code null} when no loaded chunk covers the cell — the world has not arrived yet. */
    private Boolean solide(Vector3f sonde, int y) {
        sonde.y = y;
        if (!worldProvider.isBlockRelevant(sonde)) {
            return null;
        }
        Block bloc = worldProvider.getBlock(sonde);
        return bloc != null && !bloc.isPenetrable();
    }
}
