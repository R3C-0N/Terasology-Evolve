// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.logic.location.LocationComponent;
import org.terasology.engine.physics.components.shapes.BoxShapeComponent;
import org.terasology.engine.world.WorldProvider;

/**
 * One stride of a four-legged creature, whatever it is walking towards.
 * <p>
 * Strolling and hunting are two states of mind and one pair of legs, so the legs live here rather than in
 * either system. The body is kinematic (see {@link GravityAuthoritySystem}): a stride is moving the
 * {@link LocationComponent} and nothing else, and the fall is left to gravity, which runs over the same
 * entities and reads the same ground. The one exception is the step up, taken here: a creature that walked
 * into a slope and waited for gravity to lift it would spend that frame inside the hill, and the probe would
 * then answer "buried" rather than "climbing".
 * <p>
 * <strong>Where it goes and where it looks are two angles.</strong> They agree while the way is clear, and
 * they part the moment a creature slides along a rock to get round it: it sidles, and it keeps looking at
 * what it is chasing. Folding them back into one would make a hunting wolf turn its head away from its prey
 * every time the ground refused a step.
 */
final class Stride {

    /** How far above the destination's floor the liquid probe sits. */
    private static final float SONDE_LIQUIDE = 0.25f;

    private Stride() {
    }

    /** Half the creature's height, which is how far its feet are below the position it is stored at. */
    static float demiHauteur(EntityRef creature) {
        BoxShapeComponent box = creature.getComponent(BoxShapeComponent.class);
        return box == null ? 0.5f : box.extents.y / 2f;
    }

    /**
     * One stride along {@code cap}, facing {@code regard}. Returns {@code false} when the way is barred and
     * nothing has moved.
     * <p>
     * A single probe answers both questions a step asks. {@link Ground#under} climbs out of whatever is solid
     * at the destination, so a floor that comes back far above the feet <em>is</em> the wall, and one that
     * comes back far below is the ledge.
     */
    static boolean avancer(WorldProvider world, EntityRef creature, LocationComponent location,
                           Vector3f position, float cap, float regard, float pas,
                           float stepUp, float dropMax) {
        float pieds = position.y - demiHauteur(creature);
        float x = position.x + (float) Math.sin(cap) * pas;
        float z = position.z + (float) Math.cos(cap) * pas;

        float sol = Ground.under(world, x, pieds, z);
        if (Float.isNaN(sol) || sol - pieds > stepUp || pieds - sol > dropMax) {
            return false;
        }
        if (Ground.liquide(world, x, sol + SONDE_LIQUIDE, z)) {
            return false;
        }

        position.x = x;
        position.z = z;
        if (sol > pieds) {
            position.y += sol - pieds;
        }
        location.setWorldPosition(position);
        location.setWorldRotation(new Quaternionf().rotationY(regard));
        creature.saveComponent(location);
        return true;
    }

    /** Turns the body on the spot, for a creature that is standing, blocked, or biting. */
    static void tourner(EntityRef creature, LocationComponent location, float regard) {
        location.setWorldRotation(new Quaternionf().rotationY(regard));
        creature.saveComponent(location);
    }

    /** Moves {@code de} towards {@code vers} by at most {@code max} degrees, the short way round. */
    static float virer(float de, float vers, float max) {
        float ecart = vers - de;
        float tour = (float) (Math.PI * 2);
        ecart -= tour * Math.floor((ecart + Math.PI) / tour);
        float limite = max * (float) Math.PI / 180f;
        if (ecart > limite) {
            ecart = limite;
        } else if (ecart < -limite) {
            ecart = -limite;
        }
        return de + ecart;
    }
}
