// Copyright 2026 R3C-0N
// SPDX-License-Identifier: Apache-2.0
package org.terasology.bestiaire;

import org.joml.Vector3f;
import org.terasology.engine.entitySystem.entity.EntityManager;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.prefab.Prefab;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.logic.characters.CharacterMovementComponent;
import org.terasology.engine.logic.console.commandSystem.annotations.Command;
import org.terasology.engine.logic.console.commandSystem.annotations.CommandParam;
import org.terasology.engine.logic.console.commandSystem.annotations.Sender;
import org.terasology.engine.logic.location.LocationComponent;
import org.terasology.engine.logic.permission.PermissionManager;
import org.terasology.engine.network.ClientComponent;
import org.terasology.engine.registry.In;
import org.terasology.engine.utilities.Assets;
import org.terasology.module.inventory.systems.InventoryManager;

/**
 * Two commands, because a creature reaches the world by two roads.
 * <p>
 * {@code totem} hands over the object and leaves the placing to the player — it is the road the game uses.
 * {@code creature} skips it, for the sessions where the point is to measure a fight and not to walk to a
 * clearing. Both need the cheat permission, like {@code spawnPrefab} and {@code give} before them.
 */
@RegisterSystem
public class BestiaryCommands extends BaseComponentSystem {

    private static final float DEVANT = 2f;

    @In
    private Bestiary bestiary;

    @In
    private EntityManager entityManager;

    @In
    private InventoryManager inventoryManager;

    @Command(shortDescription = "Fait apparaître une créature devant vous",
            helpText = "L'espèce est celle du bestiaire : mannequin, loup, sanglier… La créature apparaît à deux "
                    + "mètres, tournée vers vous.",
            runOnServer = true,
            requiredPermission = PermissionManager.CHEAT_PERMISSION)
    public String creature(@Sender EntityRef client,
                           @CommandParam("espèce") String espece,
                           @CommandParam(value = "nombre", required = false) Integer nombre) {
        EntityRef character = personnage(client);
        if (!character.exists()) {
            return "Aucun personnage";
        }
        Prefab prefab = bestiary.creature(espece).orElse(null);
        if (prefab == null) {
            return "Espèce inconnue. Le bestiaire porte : " + String.join(", ", bestiary.species());
        }

        LocationComponent location = character.getComponent(LocationComponent.class);
        Vector3f position = location.getWorldPosition(new Vector3f());
        Vector3f direction = location.getWorldDirection(new Vector3f());
        direction.y = 0;
        if (direction.lengthSquared() < 1e-6f) {
            direction.set(0, 0, 1);
        }
        direction.normalize();

        CharacterMovementComponent mouvement = character.getComponent(CharacterMovementComponent.class);
        float demiHauteur = mouvement == null ? 0.9f : mouvement.height / 2f;

        int combien = Math.max(1, nombre == null ? 1 : nombre);
        for (int i = 0; i < combien; i++) {
            // En file, de biais, pour que dix mannequins ne se logent pas les uns dans les autres.
            Vector3f pieds = new Vector3f(position)
                    .add(direction.x * (DEVANT + i), -demiHauteur, direction.z * (DEVANT + i));
            Spawns.spawn(entityManager, prefab, pieds, -direction.x, -direction.z);
        }
        return combien + " × " + espece;
    }

    @Command(shortDescription = "Donne le totem d'apparition d'une créature",
            helpText = "Le totem se pose au sol comme un bloc, et la créature en sort. Au mannequin "
                    + "d'entraînement, l'objet est le mannequin lui-même.",
            runOnServer = true,
            requiredPermission = PermissionManager.CHEAT_PERMISSION)
    public String totem(@Sender EntityRef client,
                        @CommandParam("espèce") String espece,
                        @CommandParam(value = "nombre", required = false) Integer nombre) {
        EntityRef character = personnage(client);
        if (!character.exists()) {
            return "Aucun personnage";
        }
        Prefab creature = bestiary.creature(espece).orElse(null);
        if (creature == null) {
            return "Espèce inconnue. Le bestiaire porte : " + String.join(", ", bestiary.species());
        }
        CreatureComponent composant = creature.getComponent(CreatureComponent.class);
        Prefab objet = composant.item == null || composant.item.isEmpty()
                ? null : Assets.getPrefab(composant.item).orElse(null);
        if (objet == null) {
            return "Cette créature n'a pas de totem";
        }

        int demande = Math.max(1, nombre == null ? 1 : nombre);
        int donnes = 0;
        for (int i = 0; i < demande; i++) {
            EntityRef item = entityManager.create(objet);
            if (!inventoryManager.giveItem(character, character, item)) {
                item.destroy();
                break;
            }
            donnes++;
        }
        if (donnes == 0) {
            return "Sac plein";
        }
        return donnes + " × " + objet.getName();
    }

    private EntityRef personnage(EntityRef client) {
        ClientComponent composant = client.getComponent(ClientComponent.class);
        return composant == null ? EntityRef.NULL : composant.character;
    }
}
