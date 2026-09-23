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
import org.terasology.engine.registry.In;
import org.terasology.engine.rendering.logic.SkeletalMeshComponent;

import java.util.HashMap;
import java.util.Map;

/**
 * The legs swing at the speed the animal is actually going.
 * <p>
 * The model carries one animation — the maquette's own idle, which for a quadruped already <em>is</em> a
 * stride — so the gait is a rate, not a second clip. At rest the rate falls to zero and the legs stop; running
 * winds them up.
 * <p>
 * This is a client system and it has to be: {@code SkeletalMeshComponent} is a {@code VisualComponent} and
 * {@code animationRate} is not replicated, so setting it on the server would change nothing anyone can see.
 * Nothing is sent for it either — the speed is <em>measured</em>, from positions the client already receives.
 * The measurement is smoothed because those positions arrive at the network's cadence and not at the frame's:
 * read raw, the rate would flicker with every packet.
 */
@RegisterSystem(RegisterMode.CLIENT)
public class GaitClientSystem extends BaseComponentSystem implements UpdateSubscriberSystem {

    /** How fast the smoothed speed catches up, per second. */
    private static final float SUIVI = 6f;

    /** Below this fraction of the walking pace, the animal is standing still. */
    private static final float SEUIL = 0.1f;

    /** The legs never swing more than this many times their nominal rate. */
    private static final float RATIO_MAX = 3.5f;

    @In
    private EntityManager entityManager;

    private final Map<EntityRef, Vector3f> dernieres = new HashMap<>();
    private final Map<EntityRef, Float> vitesses = new HashMap<>();

    @Override
    public void update(float delta) {
        dernieres.keySet().removeIf(entity -> !entity.exists());
        vitesses.keySet().removeIf(entity -> !entity.exists());
        if (delta <= 0f) {
            return;
        }

        for (EntityRef creature : entityManager.getEntitiesWith(WanderComponent.class,
                SkeletalMeshComponent.class, LocationComponent.class)) {
            LocationComponent location = creature.getComponent(LocationComponent.class);
            SkeletalMeshComponent maillage = creature.getComponent(SkeletalMeshComponent.class);
            WanderComponent wander = creature.getComponent(WanderComponent.class);
            if (location == null || maillage == null || wander == null) {
                continue;
            }

            Vector3f position = location.getWorldPosition(new Vector3f());
            Vector3f avant = dernieres.get(creature);
            dernieres.put(creature, new Vector3f(position));
            if (avant == null) {
                continue;
            }

            float dx = position.x - avant.x;
            float dz = position.z - avant.z;
            float brute = (float) Math.sqrt(dx * dx + dz * dz) / delta;
            float lissee = vitesses.getOrDefault(creature, 0f);
            lissee += (brute - lissee) * Math.min(1f, delta * SUIVI);
            vitesses.put(creature, lissee);

            float nominal = wander.speed <= 0f ? 1f : wander.speed;
            float ratio = lissee / nominal;
            // Le composant n'est pas replique : on l'ecrit sans `saveComponent`, qui ne servirait qu'a
            // salir l'entite pour un champ que personne n'envoie.
            maillage.animationRate = ratio < SEUIL ? 0f : Math.min(RATIO_MAX, ratio);
        }
    }
}
