// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.joml.Vector3f;
import org.terasology.engine.core.Time;
import org.terasology.engine.entitySystem.entity.EntityBuilder;
import org.terasology.engine.entitySystem.entity.EntityManager;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterMode;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.entitySystem.systems.UpdateSubscriberSystem;
import org.terasology.engine.logic.location.LocationComponent;
import org.terasology.engine.physics.components.shapes.BoxShapeComponent;
import org.terasology.engine.registry.In;
import org.terasology.engine.rendering.logic.FloatingTextComponent;
import org.terasology.engine.utilities.random.FastRandom;
import org.terasology.engine.utilities.random.Random;
import org.terasology.gestalt.entitysystem.event.ReceiveEvent;
import org.terasology.nui.Color;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Draws the damage a creature just took, where it took it.
 * <p>
 * {@code FloatingTextComponent} is a visual component: it is never replicated, so each client builds its own
 * text entity, local and unpersisted. That is the same shape {@code NameTagClientSystem} uses for name tags,
 * and the reason the entity is built here rather than on the server.
 * <p>
 * The numbers rise and vanish; they do not fade, because the component carries no alpha. A sideways jitter
 * keeps two hits in the same second from landing on the same pixels.
 */
@RegisterSystem(RegisterMode.CLIENT)
public class DamageNumberClientSystem extends BaseComponentSystem implements UpdateSubscriberSystem {

    private static final float DUREE = 1.1f;
    private static final float MONTEE = 1.2f;
    private static final Color TEINTE = new Color(0xE2604AFF);

    @In
    private EntityManager entityManager;

    @In
    private Time time;

    private final List<Nombre> nombres = new ArrayList<>();
    private final Random random = new FastRandom();

    @ReceiveEvent
    public void onDamageNumber(DamageNumberEvent event, EntityRef creature, CreatureComponent component) {
        LocationComponent location = creature.getComponent(LocationComponent.class);
        if (location == null) {
            return;
        }
        BoxShapeComponent box = creature.getComponent(BoxShapeComponent.class);
        float haut = box == null ? 1f : box.extents.y / 2f;

        Vector3f position = location.getWorldPosition(new Vector3f())
                .add(random.nextFloat(-0.3f, 0.3f), haut * 0.8f, random.nextFloat(-0.3f, 0.3f));

        FloatingTextComponent texte = new FloatingTextComponent();
        texte.text = "-" + event.getAmount();
        texte.textColor = TEINTE;
        texte.scale = 1.2f;

        EntityBuilder builder = entityManager.newBuilder();
        builder.addComponent(texte);
        builder.addComponent(new LocationComponent(position));
        builder.setPersistent(false);
        nombres.add(new Nombre(builder.build(), time.getGameTime() + DUREE));
    }

    @Override
    public void update(float delta) {
        Iterator<Nombre> it = nombres.iterator();
        while (it.hasNext()) {
            Nombre nombre = it.next();
            if (!nombre.entite.exists() || time.getGameTime() >= nombre.fin) {
                nombre.entite.destroy();
                it.remove();
                continue;
            }
            LocationComponent location = nombre.entite.getComponent(LocationComponent.class);
            if (location != null) {
                location.setWorldPosition(location.getWorldPosition(new Vector3f()).add(0, MONTEE * delta, 0));
                nombre.entite.saveComponent(location);
            }
        }
    }

    @Override
    public void shutdown() {
        nombres.forEach(nombre -> nombre.entite.destroy());
        nombres.clear();
    }

    private static final class Nombre {
        private final EntityRef entite;
        private final float fin;

        private Nombre(EntityRef entite, float fin) {
            this.entite = entite;
            this.fin = fin;
        }
    }
}
