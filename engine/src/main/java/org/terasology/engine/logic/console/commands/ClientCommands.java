// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.logic.console.commands;

import org.joml.Vector3f;
import org.joml.Vector3i;
import org.terasology.engine.core.PathManager;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.entitySystem.systems.UpdateSubscriberSystem;
import org.terasology.engine.input.cameraTarget.CameraTargetSystem;
import org.terasology.engine.logic.console.commandSystem.annotations.Command;
import org.terasology.engine.logic.console.commandSystem.annotations.CommandParam;
import org.terasology.engine.logic.console.commandSystem.annotations.Sender;
import org.terasology.engine.logic.location.LocationComponent;
import org.terasology.engine.logic.players.LocalPlayer;
import org.terasology.engine.logic.players.SetDirectionEvent;
import org.terasology.engine.logic.players.StaticSpawnLocationComponent;
import org.terasology.engine.logic.players.event.WorldtimeResetEvent;
import org.terasology.engine.logic.permission.PermissionManager;
import org.terasology.engine.network.ClientComponent;
import org.terasology.engine.network.NetworkSystem;
import org.terasology.engine.registry.CoreRegistry;
import org.terasology.engine.registry.In;
import org.terasology.engine.rendering.opengl.ScreenGrabber;
import org.terasology.engine.world.WorldProvider;
import org.terasology.engine.world.sun.CelestialSystem;

import java.util.Locale;

/**
 * This class contains basic client commands for debugging eg.
 * for displaying debug information for the target at which is camera pointing at
 * and for setting current world time for the local player in days
 */
@RegisterSystem
public class ClientCommands extends BaseComponentSystem implements UpdateSubscriberSystem {
    @In
    private CameraTargetSystem cameraTargetSystem;

    @In
    private WorldProvider worldProvider;

    @In
    private NetworkSystem networkSystem;

    @In
    private CelestialSystem celestialSystem;

    @In
    private LocalPlayer localPlayer;

    /**
     * Frames left before the armed screenshot is read back, or zero when none is pending.
     * <p>
     * The grabber's flag has to be raised <em>before</em> a frame is drawn, because it decides the
     * size of the buffer that frame renders into; the read has to happen after. Reading in the same
     * breath as arming gives a black image, which is what this counter is here to avoid. Two frames
     * rather than one because the order in which systems update is not defined, and one frame would
     * be a race against whichever system happens to tick first.
     */
    private int screenshotDelay;

    @Override
    public void update(float delta) {
        if (screenshotDelay > 0 && --screenshotDelay == 0) {
            ScreenGrabber grabber = CoreRegistry.get(ScreenGrabber.class);
            if (grabber != null) {
                grabber.saveScreenshot();
            }
        }
    }

    /**
     * Displays debug information on the target entity for the target the camera is pointing at
     * @return String containing debug information on the entity
     */
    @Command(shortDescription = "Displays debug information on the target entity")
    public String debugTarget() {
        EntityRef cameraTarget = cameraTargetSystem.getTarget();
        return cameraTarget.toFullDescription();
    }

    /**
     * Aims the character, in degrees, without touching the mouse.
     * <p>
     * {@link SetDirectionEvent} and its receiver in {@code LocalPlayerSystem} have always existed;
     * nothing in the engine ever sent one. Until now the only way to aim was relative mouse motion,
     * which is open-loop — it needs the current pitch to be known, and Windows pointer acceleration
     * makes the step non-linear — so aiming a screenshot was guesswork.
     * <p>
     * Yaw wraps at 360; pitch is clamped to +/-89 by the receiver and counts positive
     * <em>downwards</em>, which is not the obvious convention and was established by round trip
     * against the running game rather than assumed. Both are degrees, matching
     * {@code CharacterMoveInputEvent}.
     *
     * @param yaw degrees around the vertical axis
     * @param pitch degrees below the horizon
     * @return what was set, or why it could not be
     */
    @Command(shortDescription = "Points the character at a yaw and pitch, in degrees",
            helpText = "Absolute, unlike mouse motion. Yaw wraps at 360, pitch is clamped to +/-89.",
            requiredPermission = PermissionManager.NO_PERMISSION)
    public String look(@CommandParam("yaw") float yaw, @CommandParam("pitch") float pitch) {
        if (!localPlayer.isValid()) {
            return "No local player to aim: this command only exists inside a game.";
        }
        localPlayer.getCharacterEntity().send(new SetDirectionEvent(yaw, pitch));
        return String.format(Locale.ROOT, "look yaw=%.2f pitch=%.2f", yaw, pitch);
    }

    /**
     * Takes one of the game's own screenshots.
     * <p>
     * {@code takeScreenshot} only raises a flag, and <em>nothing in this tree ever acts on it</em>:
     * the flag is read in one place, to size a framebuffer, while the method that reads that buffer
     * back and writes a file is called only by the game-preview path. The F12 binding has therefore
     * been arming a flag that leads nowhere, and pressing it by hand produces no file either — which
     * is not what its name suggests. This command arms the flag and then, two frames later, performs
     * the read nobody was performing. F12 itself is left as it was.
     * <p>
     * The write is scheduled on another thread and the name is chosen at write time from the clock
     * and the resolution, so this cannot return it: take the newest file in the directory.
     * <p>
     * <b>The image comes out black in this build, and not because of this command.</b> The buffer
     * read-back is broken further down: the game's own save previews, written long before this
     * existed and through the same call, are black too — which is why the thumbnail beside a save is
     * an empty rectangle. This command is the only caller that arms and then reads, so it is the
     * place to test from once that is fixed; until then, grab the window instead.
     *
     * @return where the file will appear
     */
    @Command(shortDescription = "Takes a screenshot through the engine's own grabber",
            requiredPermission = PermissionManager.NO_PERMISSION)
    public String screenshot() {
        ScreenGrabber grabber = CoreRegistry.get(ScreenGrabber.class);
        if (grabber == null) {
            return "No screen grabber: this build renders headless.";
        }
        grabber.takeScreenshot();
        screenshotDelay = 2;
        return "screenshot armed, appears shortly in " + PathManager.getInstance().getScreenshotPath()
                + " (warning: the buffer read-back is broken in this build, the image will be black)";
    }

    /**
     * Everything needed to place a screenshot, on one parseable line.
     * <p>
     * The pieces existed separately — {@code showPosition} for the position, {@code debugTarget} for
     * a whole entity description — but nothing reported where the camera was <em>pointing</em>, and
     * that is the state one needs to reproduce a view. Yaw and pitch are inverted from the view
     * direction rather than read from {@code LocalPlayerSystem}, whose fields are private; they are
     * the same convention {@link #look} takes, so a value read here can be fed straight back.
     *
     * @return one line of {@code key=value} pairs
     */
    @Command(shortDescription = "Position, aim and targeted block on one line",
            requiredPermission = PermissionManager.NO_PERMISSION)
    public String showView() {
        if (!localPlayer.isValid()) {
            return "No local player: this command only exists inside a game.";
        }
        Vector3f pos = localPlayer.getPosition(new Vector3f());
        Vector3f dir = localPlayer.getViewDirection(new Vector3f());

        // Measured by round trip rather than derived: feeding look(y, p) back through here has to
        // return y and p. The forward axis is +Z, and pitch counts positive downwards — both the
        // opposite of the first guess, which is why this was checked against the running game.
        // asin of anything a hair outside [-1, 1] is NaN, and a normalised vector can land there.
        float pitch = (float) -Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, dir.y))));
        float yaw = (float) Math.toDegrees(Math.atan2(dir.x, dir.z));

        StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
                "pos=%.2f,%.2f,%.2f dir=%.4f,%.4f,%.4f yaw=%.2f pitch=%.2f",
                pos.x, pos.y, pos.z, dir.x, dir.y, dir.z, yaw, pitch));
        if (cameraTargetSystem.isTargetAvailable() && cameraTargetSystem.isBlock()) {
            Vector3i block = cameraTargetSystem.getTargetBlockPosition();
            out.append(String.format(Locale.ROOT, " block=%d,%d,%d uri=%s",
                    block.x, block.y, block.z, worldProvider.getBlock(block).getURI()));
        } else {
            out.append(" block=none");
        }
        return out.toString();
    }

    /**
     * Prints the stored sunlight and block light down a column, one line per height.
     * <p>
     * Written to settle whether black terrain means the light data is wrong or the mesh reads it
     * from the wrong place: the vertex attribute is baked from these very cells, so if they hold
     * fifteen and the ground still renders black, the fault is in the baking, not the propagation.
     *
     * @param x world column
     * @param z world column
     * @param from lowest height, inclusive
     * @param to highest height, inclusive
     * @return one line per height, coarse to read but unambiguous
     */
    @Command(shortDescription = "Stored sunlight and block light down a column",
            requiredPermission = PermissionManager.NO_PERMISSION)
    public String sunlightColumn(@CommandParam("x") int x, @CommandParam("z") int z,
                                 @CommandParam("from") int from, @CommandParam("to") int to) {
        StringBuilder out = new StringBuilder();
        Vector3i pos = new Vector3i();
        for (int y = Math.min(from, to); y <= Math.max(from, to); y++) {
            pos.set(x, y, z);
            out.append(String.format(Locale.ROOT, "y=%d sun=%d light=%d %s%n",
                    y, worldProvider.getSunlight(pos), worldProvider.getLight(pos),
                    worldProvider.getBlock(pos).getURI()));
        }
        return out.toString();
    }

    /**
     * Sets the current world time for the local player in days
     * @param day Float containing day to be set
     * @return String message containing message to notify user
     */
    @Command(shortDescription = "Sets the current world time for the local player in days",
            requiredPermission = PermissionManager.CHEAT_PERMISSION)
    public String setWorldTime(@CommandParam("day") float day, @Sender EntityRef sender) {
        worldProvider.getTime().setDays(day);
        sender.send(new WorldtimeResetEvent(day));
        return "World time changed";
    }

    /**
     * Permanently halts the sun's position and angle
     * @param day Float containing day to be set
     * @return String message containing message to notify user
     */
    @Command(shortDescription = "Permanently halts the sun's position and angle", requiredPermission = PermissionManager.CHEAT_PERMISSION)
    public  String toggleSunHalting(@CommandParam("day") float day) {
        celestialSystem.toggleSunHalting(day);

        if (celestialSystem.isSunHalted()) {
            return "Permanently set the sun's position.";
        } else {
            return "Disabled the sun's halt.";
        }
    }
    /**
     * Sets the spawn location for the client to the current location
     * @return String containing debug information on the entity
     */
    @Command(shortDescription = "Sets the spawn location for the client to the current location",
            runOnServer = true, requiredPermission = PermissionManager.CHEAT_PERMISSION)
    public String setSpawnLocation(@Sender EntityRef sender) {
        EntityRef clientInfo = sender.getComponent(ClientComponent.class).clientInfo;
        StaticSpawnLocationComponent staticSpawnLocationComponent = new StaticSpawnLocationComponent();
        if (clientInfo.hasComponent(StaticSpawnLocationComponent.class)) {
            staticSpawnLocationComponent = clientInfo.getComponent(StaticSpawnLocationComponent.class);
        }
        staticSpawnLocationComponent.position = sender
                .getComponent(ClientComponent.class)
                .character
                .getComponent(LocationComponent.class)
                .getWorldPosition(new Vector3f());
        clientInfo.addOrSaveComponent(staticSpawnLocationComponent);
        return "Set spawn location to- " + staticSpawnLocationComponent.position;
    }
}
