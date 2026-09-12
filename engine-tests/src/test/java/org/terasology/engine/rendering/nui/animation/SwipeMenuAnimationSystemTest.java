// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.animation;

import org.junit.jupiter.api.Test;
import org.terasology.joml.geom.Rectanglei;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le balayage des menus, epingle par un test plutot que par l'oeil.
 *
 * <p>Tout le paquet {@code rendering/nui/animation} etait depourvu de test, et
 * c'est ce qui a laisse passer le defaut : l'animation ne rendait AUCUNE image
 * intermediaire, l'ecran tenait puis coupait net. Rien dans la suite ne le
 * voyait, parce que rien n'affirmait que {@link
 * SwipeMenuAnimationSystem#animateRegion} BOUGE d'une image a l'autre.
 *
 * <p>L'horloge est injectee : un test qui dort ne mesure pas une duree, il la
 * subit.
 */
public class SwipeMenuAnimationSystemTest {

    private static final float DURATION = 0.25f;
    private static final Rectanglei SCREEN = new Rectanglei(0, 0, 800, 600);

    /** Horloge pilotee a la main, en nanosecondes. */
    private static final class FakeClock {
        private final AtomicLong nanos = new AtomicLong();

        long get() {
            return nanos.get();
        }

        void advance(float seconds) {
            nanos.addAndGet((long) (seconds * 1_000_000_000L));
        }
    }

    private static SwipeMenuAnimationSystem system(FakeClock clock) {
        return new SwipeMenuAnimationSystem(DURATION,
                SwipeMenuAnimationSystem.Direction.LEFT_TO_RIGHT, clock::get);
    }

    /**
     * L'assertion qui manquait : la region se DEPLACE entre deux images.
     *
     * <p>Un test qui verifie seulement que l'auditeur finit par partir passe
     * meme quand l'animation saute d'un bloc — c'est exactement le defaut
     * qu'on repare.
     */
    @Test
    public void regionMovesBetweenFrames() {
        FakeClock clock = new FakeClock();
        SwipeMenuAnimationSystem swipe = system(clock);
        swipe.onEnd(() -> { });
        swipe.triggerToNext();

        int previous = swipe.animateRegion(SCREEN).minX;
        int moves = 0;
        for (int i = 0; i < 8; i++) {
            step(swipe, clock, DURATION / 16f);
            int now = swipe.animateRegion(SCREEN).minX;
            if (now != previous) {
                moves++;
            }
            previous = now;
        }
        assertTrue(moves >= 5, "la region doit bouger a presque chaque image, vu " + moves);
    }

    /** L'auditeur ne part pas au premier pas : il attend la duree entiere. */
    @Test
    public void endListenerWaitsForTheFullDuration() {
        FakeClock clock = new FakeClock();
        SwipeMenuAnimationSystem swipe = system(clock);
        AtomicInteger ended = new AtomicInteger();
        swipe.onEnd(ended::incrementAndGet);
        swipe.triggerToNext();

        step(swipe, clock, DURATION / 4f);
        assertEquals(0, ended.get(), "l'auditeur est parti des la premiere image");

        for (int i = 0; i < 4; i++) {
            step(swipe, clock, DURATION / 4f);
        }
        assertEquals(1, ended.get(), "l'auditeur doit partir une fois, a la fin");
    }

    /**
     * Le temps est du temps REEL, pas un tic par image.
     *
     * <p>L'ancienne version avancait de 35 ms a chaque appel quand le delta
     * recu etait nul — horloge de jeu figee. Sa vitesse suivait donc la cadence
     * d'affichage. Ici l'horloge n'avance pas : l'animation ne doit pas avancer
     * non plus, quel que soit le nombre d'appels.
     */
    @Test
    public void aFrozenClockDoesNotAdvanceTheAnimation() {
        FakeClock clock = new FakeClock();
        SwipeMenuAnimationSystem swipe = system(clock);
        AtomicInteger ended = new AtomicInteger();
        swipe.onEnd(ended::incrementAndGet);
        swipe.triggerToNext();

        int start = swipe.animateRegion(SCREEN).minX;
        for (int i = 0; i < 200; i++) {
            swipe.update(0f);
        }
        assertEquals(0, ended.get(), "200 images sur une horloge figee ont termine l'animation");
        assertEquals(start, swipe.animateRegion(SCREEN).minX, "la region a bouge sans que le temps passe");
    }

    /** Une horloge figee cote jeu, mais du temps reel qui passe : ca avance. */
    @Test
    public void realTimeDrivesTheAnimationWhenGameDeltaIsZero() {
        FakeClock clock = new FakeClock();
        SwipeMenuAnimationSystem swipe = system(clock);
        AtomicInteger ended = new AtomicInteger();
        swipe.onEnd(ended::incrementAndGet);
        swipe.triggerToNext();

        int start = swipe.animateRegion(SCREEN).minX;
        clock.advance(DURATION / 5f);
        swipe.update(0f);
        assertNotEquals(start, swipe.animateRegion(SCREEN).minX,
                "le temps reel a passe, la region devrait avoir bouge");

        for (int i = 0; i < 5; i++) {
            clock.advance(DURATION / 5f);
            swipe.update(0f);
        }
        assertEquals(1, ended.get());
    }

    /** {@code skip()} amene a l'etat final et ne declenche qu'une fois. */
    @Test
    public void skipEndsTheAnimationExactlyOnce() {
        FakeClock clock = new FakeClock();
        SwipeMenuAnimationSystem swipe = system(clock);
        AtomicInteger ended = new AtomicInteger();
        swipe.onEnd(ended::incrementAndGet);
        swipe.triggerToNext();

        swipe.skip();
        assertEquals(1, ended.get());
        swipe.skip();
        assertEquals(1, ended.get(), "skip() a redeclenche l'auditeur");
    }

    private static void step(SwipeMenuAnimationSystem swipe, FakeClock clock, float seconds) {
        clock.advance(seconds);
        swipe.update(seconds);
    }
}
