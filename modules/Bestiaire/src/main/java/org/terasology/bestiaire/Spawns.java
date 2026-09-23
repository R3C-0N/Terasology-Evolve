// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.terasology.engine.entitySystem.entity.EntityManager;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.prefab.Prefab;
import org.terasology.engine.physics.components.shapes.BoxShapeComponent;

/**
 * Putting a creature on the ground.
 * <p>
 * A creature is centred on its collision box — mesh and box have to share an origin — while everything the
 * player points at is a <em>surface</em>. The half height is therefore added here, once, and read from the
 * prefab rather than written down: the generator already sizes the box from the model, and a second copy of
 * that number would decide in silence whether a creature stands on the floor or sinks into it.
 */
final class Spawns {

    private Spawns() {
    }

    /**
     * @param feet where the creature's feet go
     * @param faceX x of the direction the creature should look towards
     * @param faceZ z of the same direction; the model's front is +z
     */
    static EntityRef spawn(EntityManager entityManager, Prefab prefab, Vector3f feet, float faceX, float faceZ) {
        BoxShapeComponent box = prefab.getComponent(BoxShapeComponent.class);
        float height = box == null ? 1f : box.extents.y;
        Vector3f position = new Vector3f(feet).add(0, height / 2f, 0);
        Quaternionf rotation = new Quaternionf();
        if (faceX * faceX + faceZ * faceZ > 1e-6f) {
            rotation.rotationY((float) Math.atan2(faceX, faceZ));
        }
        return entityManager.create(prefab, position, rotation);
    }
}
