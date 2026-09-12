// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.rendering.nui.animation;

import org.terasology.joml.geom.Rectanglei;

import java.util.function.Supplier;

/**
 * Forwards all calls to a {@link MenuAnimationSystem} from a provider.
 *
 * <p>Le fournisseur est consulte UNE FOIS par transition, et le systeme retenu
 * sert ensuite a tous les appels. Sans ce verrou, chaque appel transmis
 * reinterroge le fournisseur, et une transition peut se scinder entre deux
 * objets : {@link org.terasology.engine.rendering.nui.CoreScreenLayer} pose son
 * auditeur puis declenche, coup sur coup, et il suffit que le reglage bascule
 * entre les deux — un clic sur la case a cocher des reglages video — pour que
 * l'auditeur soit pose sur un systeme et le declenchement parte sur l'autre. La
 * poussee d'ecran est alors perdue en silence, et le menu cesse de repondre.
 *
 * <p>Le verrou se prend sur {@link #onEnd}, qui est le PREMIER appel de toute
 * transition voulue par le joueur, et il est repris a chacune : un changement de
 * reglage prend donc effet a la navigation suivante.
 */
public class DeferredMenuAnimationSystem implements MenuAnimationSystem {

    private final Supplier<MenuAnimationSystem> provider;

    /** Le systeme retenu pour la transition en cours. */
    private MenuAnimationSystem latched;

    public DeferredMenuAnimationSystem(Supplier<MenuAnimationSystem> provider) {
        this.provider = provider;
    }

    @Override
    public void triggerFromPrev() {
        getSystem().triggerFromPrev();
    }

    @Override
    public void triggerToPrev() {
        getSystem().triggerToPrev();
    }

    @Override
    public void triggerFromNext() {
        getSystem().triggerFromNext();
    }

    @Override
    public void triggerToNext() {
        getSystem().triggerToNext();
    }

    @Override
    public void onEnd(Runnable newListener) {
        latched = provider.get();
        latched.onEnd(newListener);
    }

    @Override
    public void update(float delta) {
        getSystem().update(delta);
    }

    @Override
    public void skip() {
        getSystem().skip();
    }

    @Override
    public void stop() {
        getSystem().stop();
    }

    @Override
    public Rectanglei animateRegion(Rectanglei rc) {
        return getSystem().animateRegion(rc);
    }

    /**
     * Le systeme verrouille, ou celui du fournisseur tant qu'aucune transition
     * n'a encore ete ouverte — le cas des entrees en scene declenchees par le
     * gestionnaire lui-meme, qui ne passent pas par {@link #onEnd}.
     */
    private MenuAnimationSystem getSystem() {
        return latched != null ? latched : provider.get();
    }
}
