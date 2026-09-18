// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.logic.characters;

import org.joml.Vector3f;
import org.terasology.engine.entitySystem.entity.EntityManager;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterMode;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.entitySystem.systems.UpdateSubscriberSystem;
import org.terasology.engine.registry.In;
import org.terasology.engine.rendering.assets.animation.MeshAnimation;
import org.terasology.engine.rendering.logic.SkeletalMeshComponent;

import java.util.List;

/**
 * Plays a character's walking animation while it moves and its standing animation while it does not.
 * <p>
 * The engine ships {@link StandComponent} and {@link WalkComponent} as containers for those two animation pools, but
 * nothing here ever read them: the behaviour tree nodes that do live in the Behaviors module. This system is that
 * missing reader, driven by the character's own speed rather than by a behaviour tree - which is what a player
 * character needs, since no tree drives it.
 * <p>
 * Client side only: the animation is pure appearance, and every client can work it out from the replicated velocity.
 */
@RegisterSystem(RegisterMode.CLIENT)
public class CharacterAnimationSystem extends BaseComponentSystem implements UpdateSubscriberSystem {

    /**
     * Horizontal speed above which the character counts as walking, in blocks per second. A walking player does about
     * five; this only has to sit above the residual drift of a character settling on the ground.
     */
    private static final float WALKING_SPEED = 0.35f;

    @In
    private EntityManager entityManager;

    @Override
    public void update(float delta) {
        for (EntityRef character : entityManager.getEntitiesWith(VisualCharacterComponent.class,
                CharacterMovementComponent.class)) {
            EntityRef body = character.getComponent(VisualCharacterComponent.class).visualCharacter;
            if (!body.exists()) {
                continue;
            }
            SkeletalMeshComponent skeleton = body.getComponent(SkeletalMeshComponent.class);
            if (skeleton == null) {
                continue;
            }

            Vector3f velocity = character.getComponent(CharacterMovementComponent.class).getVelocity();
            boolean walking = velocity.x * velocity.x + velocity.z * velocity.z > WALKING_SPEED * WALKING_SPEED;

            MeshAnimation wanted = firstAnimation(character, walking);
            if (wanted == null || wanted.equals(skeleton.animation)) {
                continue;
            }
            skeleton.animation = wanted;
            skeleton.animationTime = 0;
            skeleton.loop = true;
            body.saveComponent(skeleton);
        }
    }

    private MeshAnimation firstAnimation(EntityRef character, boolean walking) {
        List<MeshAnimation> pool;
        if (walking) {
            WalkComponent walk = character.getComponent(WalkComponent.class);
            pool = (walk != null) ? walk.animationPool : null;
        } else {
            StandComponent stand = character.getComponent(StandComponent.class);
            pool = (stand != null) ? stand.animationPool : null;
        }
        return (pool == null || pool.isEmpty()) ? null : pool.get(0);
    }
}
