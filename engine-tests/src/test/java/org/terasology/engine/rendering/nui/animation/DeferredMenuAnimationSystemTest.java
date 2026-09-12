// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.animation;

import org.junit.jupiter.api.Test;
import org.terasology.joml.geom.Rectanglei;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Le relais qui choisit entre le balayage et la bascule instantanee.
 *
 * <p>Deux garanties, et elles tirent en sens contraire : le chemin instantane
 * doit rester instantane — sinon reparer l'animation coute une image a ceux qui
 * l'ont eteinte — et une transition ne doit jamais se scinder entre les deux
 * systemes, sous peine de perdre la navigation en silence.
 */
public class DeferredMenuAnimationSystemTest {

    private static final Rectanglei SCREEN = new Rectanglei(0, 0, 800, 600);

    /**
     * Drapeau eteint : la bascule se fait AVANT que le declenchement rende la
     * main, donc dans la frame du clic.
     */
    @Test
    public void theInstantPathStaysSynchronous() {
        MenuAnimationSystem stub = new MenuAnimationSystemStub();
        DeferredMenuAnimationSystem deferred = new DeferredMenuAnimationSystem(() -> stub);

        AtomicBoolean switched = new AtomicBoolean();
        deferred.onEnd(() -> switched.set(true));
        deferred.triggerToNext();

        assertEquals(true, switched.get(),
                "la bascule doit avoir eu lieu avant le retour de triggerToNext()");
        assertSame(SCREEN, deferred.animateRegion(SCREEN),
                "le chemin instantane ne doit rien transformer");
    }

    /**
     * Le reglage bascule entre la pose de l'auditeur et le declenchement.
     *
     * <p>C'est un clic sur la case a cocher des reglages video, rien de plus
     * exotique. Sans verrou, l'auditeur reste sur un systeme et le
     * declenchement part sur l'autre : l'ecran n'est jamais pousse, et le menu
     * a l'air casse.
     */
    @Test
    public void aTransitionIsNeverSplitBetweenTwoSystems() {
        MenuAnimationSystem swipe = new SwipeMenuAnimationSystem(0.12f,
                SwipeMenuAnimationSystem.Direction.LEFT_TO_RIGHT);
        MenuAnimationSystem instant = new MenuAnimationSystemStub();
        AtomicBoolean animated = new AtomicBoolean(false);
        DeferredMenuAnimationSystem deferred =
                new DeferredMenuAnimationSystem(() -> animated.get() ? swipe : instant);

        AtomicInteger navigations = new AtomicInteger();
        deferred.onEnd(navigations::incrementAndGet);
        animated.set(true);             // le joueur coche la case entre les deux
        deferred.triggerToNext();

        assertEquals(1, navigations.get(),
                "la navigation a ete perdue : auditeur et declenchement sur deux systemes");
    }

    /** Le reglage change bien d'effet, mais a la transition suivante. */
    @Test
    public void theSettingTakesEffectOnTheNextTransition() {
        MenuAnimationSystem swipe = new SwipeMenuAnimationSystem(0.12f,
                SwipeMenuAnimationSystem.Direction.LEFT_TO_RIGHT);
        MenuAnimationSystem instant = new MenuAnimationSystemStub();
        AtomicBoolean animated = new AtomicBoolean(false);
        DeferredMenuAnimationSystem deferred =
                new DeferredMenuAnimationSystem(() -> animated.get() ? swipe : instant);

        AtomicInteger navigations = new AtomicInteger();
        deferred.onEnd(navigations::incrementAndGet);
        deferred.triggerToNext();
        assertEquals(1, navigations.get(), "drapeau eteint : bascule immediate");

        animated.set(true);
        deferred.onEnd(navigations::incrementAndGet);
        deferred.triggerToNext();
        assertEquals(1, navigations.get(),
                "drapeau allume : la bascule doit maintenant attendre la fin de l'animation");
    }
}
