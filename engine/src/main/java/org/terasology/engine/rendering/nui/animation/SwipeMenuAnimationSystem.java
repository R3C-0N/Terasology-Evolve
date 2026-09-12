// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.animation;

import org.terasology.engine.rendering.animation.Animation;
import org.terasology.engine.rendering.animation.AnimationListener;
import org.terasology.engine.rendering.animation.TimeModifiers;
import org.terasology.joml.geom.Rectanglei;

import java.util.function.LongSupplier;

/**
 * Controls animations to and from different screens.
 */
public class SwipeMenuAnimationSystem implements MenuAnimationSystem {

    /**
     * Plafond d'un pas, en secondes : sous une charge lourde une image peut
     * couvrir un temps arbitraire, et l'animation sauterait d'un bloc.
     */
    private static final float MAX_STEP = 0.1f;

    public enum Direction {
        LEFT_TO_RIGHT(1, 0),
        RIGHT_TO_LEFT(-1, 0),
        TOP_TO_BOTTOM(0, -1),
        BOTTOM_TO_TOP(0, 1);

        private final float horzScale;
        private final float vertScale;

        Direction(float horzScale, float vertScale) {
            this.horzScale = horzScale;
            this.vertScale = vertScale;
        }

        public float getHorzScale() {
            return horzScale;
        }

        public float getVertScale() {
            return vertScale;
        }
    }

    private final Direction direction;

    private final Animation flyIn;
    private final Animation flyOut;

    /**
     * Source de temps REEL, en nanosecondes.
     *
     * <p>Les menus tournent sur une horloge de jeu qui n'avance pas : le menu
     * principal n'a pas de monde, et le menu de pause a mis le temps en pause.
     * Le {@code delta} recu vaut alors zero. L'ancienne version compensait par
     * un tic constant de 35 ms A CHAQUE IMAGE, si bien que la vitesse de
     * l'animation suivait la cadence d'affichage au lieu de suivre le temps.
     * On prend donc le temps reel, qui est ce qu'on voulait mesurer.
     */
    private final LongSupplier clock;

    private long lastNanos;
    private boolean clockPrimed;

    /**
     * Ce que {@code CoreScreenLayer} veut voir arriver a la fin de la
     * transition — pousser ou depiler un ecran. Garde ici pour pouvoir le
     * detacher pendant une annulation, puis le remettre.
     */
    private Runnable endListener = () -> { };

    private float scale;

    /**
     * Creates default animations
     * @param duration the duration of the animation in seconds
     */
    public SwipeMenuAnimationSystem(float duration) {
        this(duration, Direction.LEFT_TO_RIGHT);
    }

    /**
     * Creates default animations
     * @param duration the duration of the animation in seconds
     * @param direction the swipe direction
     */
    public SwipeMenuAnimationSystem(float duration, Direction direction) {
        this(duration, direction, System::nanoTime);
    }

    /**
     * Creates default animations
     * @param duration the duration of the animation in seconds
     * @param direction the swipe direction
     * @param clock source of real time, in nanoseconds
     */
    public SwipeMenuAnimationSystem(float duration, Direction direction, LongSupplier clock) {
        // down from 1 (fast) to 0 (slow)
        flyIn = Animation.once(v -> scale = v, duration, TimeModifiers.inverse().andThen(TimeModifiers.square()));

        // down from 0 (slow) to -1 (fast)
        flyOut = Animation.once(v -> scale = -v, duration, TimeModifiers.square());

        this.direction = direction;
        this.clock = clock;
    }

    /**
     * Trigger animation from previous screen to this one
     */
    @Override
    public void triggerFromPrev() {
        primeClock();
        // Le declenchement ne doit jamais etre avale : sans cette annulation,
        // un clic tombe pendant la transition inverse ne pousserait aucun ecran.
        cancelSilently(flyOut);
        if (flyIn.isStopped()) {
            flyIn.setForwardMode();
            flyIn.start();
        }
    }

    /**
     * Trigger animation from this one back to the previous screen
     */
    @Override
    public void triggerToPrev() {
        primeClock();
        // Le declenchement ne doit jamais etre avale : sans cette annulation,
        // un clic tombe pendant la transition inverse ne pousserait aucun ecran.
        cancelSilently(flyOut);
        if (flyIn.isStopped()) {
            flyIn.setReverseMode();
            flyIn.start();
        }
    }

    /**
     * Trigger animation from the next screen to this one
     */
    @Override
    public void triggerFromNext() {
        primeClock();
        // Le declenchement ne doit jamais etre avale : sans cette annulation,
        // un clic tombe pendant la transition inverse ne pousserait aucun ecran.
        cancelSilently(flyIn);
        if (flyOut.isStopped()) {
            flyOut.setReverseMode();
            flyOut.start();
        }
    }

    /**
     * Trigger animation from this one to the next screen
     */
    @Override
    public void triggerToNext() {
        primeClock();
        // Le declenchement ne doit jamais etre avale : sans cette annulation,
        // un clic tombe pendant la transition inverse ne pousserait aucun ecran.
        cancelSilently(flyIn);
        if (flyOut.isStopped()) {
            flyOut.setForwardMode();
            flyOut.start();
        }
    }

    @Override
    public void skip() {
        // set the animation to the end point and trigger onEnd()
        if (flyIn.isRunning()) {
            flyIn.update(flyIn.getDuration());
        }
        if (flyOut.isRunning()) {
            flyOut.update(flyOut.getDuration());
        }
    }

    @Override
    public void stop() {
        if (flyOut.isRunning()) {
            flyOut.setReverseMode();
        }
        if (flyIn.isRunning()) {
            flyIn.setForwardMode();
        }
    }

    /**
     * @param listener the listener to trigger when the animation has ended
     */
    @Override
    public void onEnd(Runnable listener) {
        this.endListener = listener;
        attachEndListener();
    }

    private void attachEndListener() {
        flyOut.removeAllListeners();
        flyOut.addListener(new AnimationListener() {
            @Override
            public void onEnd() {
                if (!flyOut.isReverse()) {
                    endListener.run();
                }
            }
        });

        flyIn.removeAllListeners();
        flyIn.addListener(new AnimationListener() {
            @Override
            public void onEnd() {
                if (flyIn.isReverse()) {
                    endListener.run();
                }
            }
        });
    }

    /**
     * Arrete une animation en vol SANS declencher son auditeur.
     *
     * <p>{@code Animation.stop()} notifie ses auditeurs. Or le meme auditeur
     * sert a l'aller et au retour : annuler un retour en cours le ferait partir,
     * puis la nouvelle animation le ferait partir une seconde fois — deux
     * navigations pour un clic. On le detache donc le temps de l'arret.
     */
    private void cancelSilently(Animation anim) {
        if (anim.isRunning()) {
            anim.removeAllListeners();
            anim.stop();
            attachEndListener();
        }
    }

    /**
     * @param delta time difference in seconds
     */
    @Override
    public void update(float delta) {
        float animDelta;

        if (delta < 0.0001f) {
            // L'horloge de jeu est a l'arret — menu principal, ou jeu en pause.
            // On se rabat sur le temps reel plutot que sur un tic par image.
            animDelta = realDelta();
        } else {
            animDelta = Math.min(delta, MAX_STEP);
            // le temps de jeu avance : l'horloge reelle reste alignee sur lui,
            // sinon le prochain retour au temps reel vaudrait un saut
            primeClock();
        }

        flyIn.update(animDelta);
        flyOut.update(animDelta);
    }

    /**
     * Temps reel ecoule depuis le dernier appel, en secondes.
     *
     * @return 0 au tout premier appel : on ne connait pas encore d'origine, et
     *         inventer un pas ferait sauter l'animation des son ouverture.
     */
    private float realDelta() {
        long now = clock.getAsLong();
        if (!clockPrimed) {
            lastNanos = now;
            clockPrimed = true;
            return 0f;
        }
        float seconds = (now - lastNanos) / 1_000_000_000f;
        lastNanos = now;
        return Math.min(Math.max(seconds, 0f), MAX_STEP);
    }

    private void primeClock() {
        lastNanos = clock.getAsLong();
        clockPrimed = true;
    }

    @Override
    public Rectanglei animateRegion(Rectanglei rc) {
        if (scale == 0.0) {
            // this should cover most of the cases
            return rc;
        }
        int left = (int) (direction.getHorzScale() * scale * rc.lengthX());
        int top = (int) (direction.getVertScale() * scale * rc.lengthY());
        return new Rectanglei(left, top, left + rc.lengthX(), top + rc.lengthY());
    }
}
