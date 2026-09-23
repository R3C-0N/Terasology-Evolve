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
import org.terasology.engine.entitySystem.systems.UpdateSubscriberSystem;
import org.terasology.engine.logic.delay.DelayManager;
import org.terasology.engine.logic.delay.DelayedActionTriggeredEvent;
import org.terasology.engine.logic.location.LocationComponent;
import org.terasology.engine.registry.In;
import org.terasology.engine.world.WorldProvider;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.block.BlockManager;
import org.terasology.gestalt.entitysystem.event.ReceiveEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Eating grass, and putting it back.
 * <p>
 * <strong>The sums that keep a meadow from becoming a desert.</strong> A herd of {@code H} beasts eating
 * every {@code period} seconds over {@code A} reachable cells is sustainable when
 * {@code regrow ≤ A × period / H}. Six mouflons, a mouthful every forty-five seconds, and a leash of
 * twenty-four blocks give about eighteen hundred cells and a ceiling of some thirteen thousand seconds; the
 * twenty minutes of {@code regrow} sit two orders of magnitude inside it. Those are the numbers that make
 * "the world does not turn to dirt" a claim rather than a hope, and they are why the population ceiling in
 * {@link FauneAuthoritySystem} matters here too: fifty herds would strip what one does not.
 * <p>
 * <strong>It will not eat under anything built.</strong> The engine records no provenance for a block — there
 * is no way to ask who put it there — so the one available test is that the cell above must be penetrable. A
 * player who fences cattle on a lawn will still get a mud patch, and that is emergent rather than broken.
 */
@RegisterSystem(RegisterMode.AUTHORITY)
public class GrazeAuthoritySystem extends BaseComponentSystem implements UpdateSubscriberSystem {

    /** Seconds between two passes. Grazing is measured in tens of seconds; sixty times a second is waste. */
    private static final float PAS = 0.5f;

    private static final String REPOUSSE = "Bestiaire:repousse";

    @In
    private EntityManager entityManager;

    @In
    private WorldProvider worldProvider;

    @In
    private BlockManager blockManager;

    @In
    private DelayManager delayManager;

    @In
    private Bestiary bestiary;

    private float horloge;

    @Override
    public void update(float delta) {
        horloge += delta;
        if (horloge < PAS) {
            return;
        }
        float ecoule = horloge;
        horloge = 0f;

        List<EntityRef> muees = new ArrayList<>();
        for (EntityRef bete : entityManager.getEntitiesWith(GrazeComponent.class, LocationComponent.class)) {
            GrazeComponent brout = bete.getComponent(GrazeComponent.class);
            LocationComponent location = bete.getComponent(LocationComponent.class);
            if (brout == null || location == null) {
                continue;
            }
            if (bete.hasComponent(ChaseComponent.class)) {
                // L'arbitrage de ChaseComponent ne vaut pas que pour les jambes : une bete qui fuit ou qui
                // chasse ne broute pas, et son horloge ne court pas non plus.
                continue;
            }
            brout.clock -= ecoule;
            if (brout.clock <= 0f) {
                brout.clock = brout.period;
                if (manger(brout, bete, location)) {
                    brout.eaten++;
                    if (brout.meals > 0 && brout.eaten >= brout.meals && !brout.becomes.isEmpty()) {
                        muees.add(bete);
                    }
                }
            }
            bete.saveComponent(brout);
        }
        muees.forEach(this::devenir);
    }

    /** One mouthful: takes the block, takes the tuft on it, and lays down the marker that puts it back. */
    private boolean manger(GrazeComponent brout, EntityRef bete, LocationComponent location) {
        Vector3f position = location.getWorldPosition(new Vector3f());
        if (!position.isFinite()) {
            return false;
        }
        float pieds = position.y - Stride.demiHauteur(bete);
        Vector3i cellule = new Vector3i((int) Math.floor(position.x),
                (int) Math.floor(pieds - 0.5f), (int) Math.floor(position.z));
        if (!worldProvider.isBlockRelevant(cellule)) {
            return false;
        }
        Block sous = worldProvider.getBlock(cellule);
        if (!estUn(brout.eats, sous)) {
            return false;
        }
        Vector3i dessus = new Vector3i(cellule.x(), cellule.y() + 1, cellule.z());
        Block haut = worldProvider.getBlock(dessus);
        if (!haut.isPenetrable()) {
            return false;
        }
        if (estUn(brout.clears, haut)) {
            worldProvider.setBlock(dessus, blockManager.getBlock(BlockManager.AIR_ID));
        }
        String rendre = sous.getURI().toString();
        worldProvider.setBlock(cellule, blockManager.getBlock(brout.leaves));

        EntityRef marqueur = entityManager.create(
                new RegrowthComponent(cellule, rendre, brout.leaves),
                new LocationComponent(new Vector3f(cellule.x() + 0.5f, cellule.y() + 0.5f,
                        cellule.z() + 0.5f)));
        delayManager.addDelayedAction(marqueur, REPOUSSE, (long) (brout.regrow * 1000f));
        return true;
    }

    /** The grass comes back where it was eaten, unless somebody has built there since. */
    @ReceiveEvent
    public void onRepousse(DelayedActionTriggeredEvent event, EntityRef marqueur,
                           RegrowthComponent repousse) {
        if (!REPOUSSE.equals(event.getActionId())) {
            return;
        }
        if (worldProvider.isBlockRelevant(repousse.cellule)
                && blockManager.getBlock(repousse.laisse).equals(worldProvider.getBlock(repousse.cellule))
                && worldProvider.getBlock(new Vector3i(repousse.cellule.x(),
                        repousse.cellule.y() + 1, repousse.cellule.z())).isPenetrable()) {
            worldProvider.setBlock(repousse.cellule, blockManager.getBlock(repousse.rendre));
        }
        marqueur.destroy();
    }

    /**
     * The shorn mouflon has its fleece back, so it is a woolly one.
     * <p>
     * Its place, its facing, its band and its wounds come across; only the body changes. The health is
     * carried as a <em>fraction</em> rather than a number, because the two prefabs need not have the same
     * maximum — and carrying nothing at all would make grazing a way to heal, which is an exploit rather than
     * a fleece.
     */
    private void devenir(EntityRef bete) {
        GrazeComponent brout = bete.getComponent(GrazeComponent.class);
        Prefab cible = bestiary.creature(brout.becomes).orElse(null);
        LocationComponent location = bete.getComponent(LocationComponent.class);
        if (cible == null || location == null) {
            return;
        }
        Vector3f position = location.getWorldPosition(new Vector3f());
        Vector3f pieds = new Vector3f(position.x, position.y - Stride.demiHauteur(bete), position.z);
        Vector3f devant = location.getWorldDirection(new Vector3f());

        org.terasology.module.health.components.HealthComponent vie =
                bete.getComponent(org.terasology.module.health.components.HealthComponent.class);
        float part = vie == null || vie.maxHealth <= 0 ? 1f
                : Math.max(0f, Math.min(1f, (float) vie.currentHealth / vie.maxHealth));
        HomeComponent foyer = bete.getComponent(HomeComponent.class);
        boolean sauvage = bete.hasComponent(SauvageComponent.class);

        EntityRef neuve = Spawns.spawn(entityManager, cible, pieds, devant.x, devant.z);
        if (sauvage) {
            neuve.addComponent(new SauvageComponent());
        }
        HomeComponent nouveau = neuve.getComponent(HomeComponent.class);
        if (nouveau != null && foyer != null) {
            nouveau.point = new Vector3f(foyer.point);
            nouveau.bande = foyer.bande;
            neuve.saveComponent(nouveau);
        }
        org.terasology.module.health.components.HealthComponent neuveVie =
                neuve.getComponent(org.terasology.module.health.components.HealthComponent.class);
        if (neuveVie != null) {
            neuveVie.currentHealth = Math.max(1, Math.round(neuveVie.maxHealth * part));
            neuve.saveComponent(neuveVie);
        }
        bete.destroy();
    }

    private boolean estUn(List<String> uris, Block bloc) {
        for (String uri : uris) {
            if (blockManager.getBlock(uri).equals(bloc)) {
                return true;
            }
        }
        return false;
    }
}
