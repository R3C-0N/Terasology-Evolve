// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.rendering.nui.animation;

import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingConfig;
import org.terasology.engine.registry.CoreRegistry;

import java.util.function.Supplier;

/**
 * Controls animations to and from different screens
 */
public final class MenuAnimationSystems {

    /**
     * Duree d'un temps de transition, en secondes.
     *
     * <p>Une transition avant en compte DEUX — le sortant part, puis l'entrant
     * arrive — donc le total est le double. A 50 images par seconde cela laisse
     * six images par temps : assez pour se lire comme un glissement, sans faire
     * attendre a chaque aller-retour dans les reglages.
     */
    private static final float SWIPE_DURATION = 0.12f;

    private MenuAnimationSystems() {
        // no instances
    }

    public static MenuAnimationSystem createDefaultSwipeAnimation() {
        RenderingConfig config = CoreRegistry.get(Config.class).getRendering();
        MenuAnimationSystem swipe = new SwipeMenuAnimationSystem(SWIPE_DURATION, SwipeMenuAnimationSystem.Direction.LEFT_TO_RIGHT);
        MenuAnimationSystem instant = new MenuAnimationSystemStub();
        Supplier<MenuAnimationSystem> provider = () -> config.isAnimatedMenu() ? swipe : instant;
        return new DeferredMenuAnimationSystem(provider);
    }
}
