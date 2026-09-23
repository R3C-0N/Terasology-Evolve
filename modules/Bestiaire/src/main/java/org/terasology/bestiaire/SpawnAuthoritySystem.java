// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.joml.Vector3f;
import org.joml.Vector3i;
import org.terasology.engine.entitySystem.entity.EntityManager;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.prefab.Prefab;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterMode;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.logic.common.ActivateEvent;
import org.terasology.engine.logic.inventory.ItemComponent;
import org.terasology.engine.math.Side;
import org.terasology.engine.registry.In;
import org.terasology.engine.utilities.Assets;
import org.terasology.engine.world.block.BlockComponent;
import org.terasology.gestalt.entitysystem.event.ReceiveEvent;

/**
 * Putting a creature into the world by using its item on a surface.
 * <p>
 * The item is spent by {@code Inventory}'s own {@code ItemAuthoritySystem}, which reads
 * {@code ItemComponent.consumedOnUse} at trivial priority — after this handler. Consuming the event on every
 * failure is therefore what keeps a botched placement from eating the item: there is no removal to undo here,
 * because there is no removal here at all.
 */
@RegisterSystem(RegisterMode.AUTHORITY)
public class SpawnAuthoritySystem extends BaseComponentSystem {

    @In
    private EntityManager entityManager;

    @ReceiveEvent(components = {SpawnerComponent.class, ItemComponent.class})
    public void onPlace(ActivateEvent event, EntityRef item, SpawnerComponent spawner) {
        BlockComponent block = event.getTarget().getComponent(BlockComponent.class);
        if (block == null) {
            // Aimed at the sky, or at something that is not a block: nothing to stand on.
            event.consume();
            return;
        }
        Prefab prefab = Assets.getPrefab(spawner.creature).orElse(null);
        if (prefab == null) {
            event.consume();
            return;
        }

        Side side = Side.inDirection(event.getHitNormal());
        Vector3i cell = new Vector3i(block.getPosition()).add(side.direction());
        // A block owns [p - 0.5, p + 0.5]: the floor of the cell it goes into is its bottom face.
        Vector3f feet = new Vector3f(cell.x, cell.y - 0.5f, cell.z);

        Vector3f direction = event.getDirection();
        Spawns.spawn(entityManager, prefab, feet, -direction.x, -direction.z);
    }
}
