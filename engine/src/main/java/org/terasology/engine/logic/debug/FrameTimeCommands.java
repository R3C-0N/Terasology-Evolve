// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.logic.debug;

import org.lwjgl.glfw.GLFW;
import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterMode;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.entitySystem.systems.RenderSystem;
import org.terasology.engine.logic.console.Console;
import org.terasology.engine.logic.console.commandSystem.annotations.Command;
import org.terasology.engine.logic.console.commandSystem.annotations.CommandParam;
import org.terasology.engine.logic.permission.PermissionManager;
import org.terasology.engine.registry.In;
import org.terasology.engine.rendering.dag.GpuPassTimer;

import java.util.Arrays;
import java.util.Locale;

/**
 * Console tools for measuring what rendering costs, from inside a running game.
 * <p>
 * The frame rate on the debug overlay is smoothed, and under vSync it only ever shows 60, 30 or 20: it cannot say what
 * a setting costs. {@code debug:frameTimes} records the real interval between frames over a window that opens after a
 * delay - long enough for the console to close, so typing the command is not part of the measurement - and prints
 * the distribution. {@code debug:renderingSetting} changes a rendering setting while the game runs, vSync included,
 * so every setting can be measured from the same place, in the same session.
 */
@RegisterSystem(RegisterMode.CLIENT)
public class FrameTimeCommands extends BaseComponentSystem implements RenderSystem {

    private static final int MAX_FRAMES = 20_000;
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    @In
    private Config config;

    @In
    private Console console;

    @In
    private Context context;

    private final long[] intervals = new long[MAX_FRAMES];
    private int recorded;
    private long lastFrame;
    /** When the current window opens and closes, in System.nanoTime; no window is open while windowStart is 0. */
    private long windowStart;
    private long windowEnd;
    private String windowLabel;

    /** Called once per rendered frame, which makes it the place to time frames. */
    @Override
    public void renderOverlay() {
        long now = System.nanoTime();
        long previous = lastFrame;
        lastFrame = now;
        if (windowStart == 0 || previous == 0 || previous < windowStart) {
            return;
        }
        if (recorded < MAX_FRAMES) {
            intervals[recorded++] = now - previous;
        }
        if (now >= windowEnd) {
            windowStart = 0;
            console.addMessage(report());
        }
    }

    @Command(value = "debug:frameTimes",
            shortDescription = "Measures frame times over a window that opens after a delay",
            helpText = "Waits delaySeconds, records every frame for seconds, then prints the frame count, the frame rate and "
                    + "the mean, median, 95th and 99th percentile and longest frame time.",
            requiredPermission = PermissionManager.NO_PERMISSION)
    public String frameTimes(@CommandParam("delaySeconds") float delaySeconds,
                             @CommandParam("seconds") float seconds,
                             @CommandParam(value = "label", required = false) String label) {
        if (seconds <= 0 || delaySeconds < 0) {
            return "The delay cannot be negative and the window must last more than 0 s.";
        }
        long now = System.nanoTime();
        recorded = 0;
        windowLabel = label == null ? "unlabelled" : label;
        windowStart = now + (long) (delaySeconds * 1_000_000_000L);
        windowEnd = windowStart + (long) (seconds * 1_000_000_000L);
        return String.format(Locale.ROOT, "Measuring frame times '%s' for %.1f s, starting in %.1f s", windowLabel, seconds,
                delaySeconds);
    }

    @Command(value = "debug:gpuTimes",
            shortDescription = "Measures how long each render pass keeps the GPU busy",
            helpText = "Waits delaySeconds, then times every render graph node on the GPU for the given number of frames, and "
                    + "prints the mean, 95th percentile and longest time of each, most expensive first.",
            requiredPermission = PermissionManager.NO_PERMISSION)
    public String gpuTimes(@CommandParam("delaySeconds") float delaySeconds,
                           @CommandParam("frames") int frames,
                           @CommandParam(value = "label", required = false) String label) {
        GpuPassTimer timer = context.get(GpuPassTimer.class);
        if (timer == null) {
            return "No world is being rendered.";
        }
        if (frames <= 0 || delaySeconds < 0) {
            return "The delay cannot be negative and at least one frame must be measured.";
        }
        String name = label == null ? "unlabelled" : label;
        timer.start((long) (delaySeconds * 1_000_000_000L), frames, name, console::addMessage);
        return String.format(Locale.ROOT, "Timing render passes '%s' on the GPU for %d frames, starting in %.1f s", name, frames,
                delaySeconds);
    }

    @Command(value = "debug:renderingSetting",
            shortDescription = "Changes a rendering setting while the game runs",
            helpText = "Booleans: vSync, reflectiveWater, dynamicShadows, dynamicShadowsPcfFiltering, cloudShadows, "
                    + "lightShafts, bloom, ssao, normalMapping, parallaxMapping, animateWater, animateGrass, eyeAdaptation, "
                    + "inscattering, outline, volumetricFog, localReflections. Numbers: frameLimit (0 for none), fboScale, "
                    + "blurIntensity.",
            requiredPermission = PermissionManager.CHEAT_PERMISSION)
    public String renderingSetting(@CommandParam("name") String name, @CommandParam("value") String value) {
        RenderingConfig rendering = config.getRendering();
        try {
            switch (name) {
                case "vSync":
                    boolean vSync = parseBoolean(value);
                    rendering.setVSync(vSync);
                    // The window reads this once, when it is created; applying it now needs the swap interval itself.
                    GLFW.glfwSwapInterval(vSync ? 1 : 0);
                    break;
                case "frameLimit":
                    rendering.setFrameLimit(Integer.parseInt(value));
                    break;
                case "fboScale":
                    rendering.setFboScale(Integer.parseInt(value));
                    break;
                case "blurIntensity":
                    rendering.setBlurIntensity(Integer.parseInt(value));
                    break;
                case "reflectiveWater":
                    rendering.setReflectiveWater(parseBoolean(value));
                    break;
                case "dynamicShadows":
                    rendering.setDynamicShadows(parseBoolean(value));
                    break;
                case "dynamicShadowsPcfFiltering":
                    rendering.setDynamicShadowsPcfFiltering(parseBoolean(value));
                    break;
                case "cloudShadows":
                    rendering.setCloudShadows(parseBoolean(value));
                    break;
                case "lightShafts":
                    rendering.setLightShafts(parseBoolean(value));
                    break;
                case "bloom":
                    rendering.setBloom(parseBoolean(value));
                    break;
                case "ssao":
                    rendering.setSsao(parseBoolean(value));
                    break;
                case "normalMapping":
                    rendering.setNormalMapping(parseBoolean(value));
                    break;
                case "parallaxMapping":
                    rendering.setParallaxMapping(parseBoolean(value));
                    break;
                case "animateWater":
                    rendering.setAnimateWater(parseBoolean(value));
                    break;
                case "animateGrass":
                    rendering.setAnimateGrass(parseBoolean(value));
                    break;
                case "eyeAdaptation":
                    rendering.setEyeAdaptation(parseBoolean(value));
                    break;
                case "inscattering":
                    rendering.setInscattering(parseBoolean(value));
                    break;
                case "outline":
                    rendering.setOutline(parseBoolean(value));
                    break;
                case "volumetricFog":
                    rendering.setVolumetricFog(parseBoolean(value));
                    break;
                case "localReflections":
                    rendering.setLocalReflections(parseBoolean(value));
                    break;
                default:
                    return "Unknown rendering setting '" + name + "'. See: help debug:renderingSetting";
            }
        } catch (IllegalArgumentException e) {
            return "Cannot set " + name + " to '" + value + "': " + e.getMessage();
        }
        return "rendering." + name + " = " + value;
    }

    private String report() {
        if (recorded == 0) {
            return "frameTimes " + windowLabel + ": no frame was rendered during the window";
        }
        long[] sorted = Arrays.copyOf(intervals, recorded);
        Arrays.sort(sorted);
        long total = 0;
        for (long interval : sorted) {
            total += interval;
        }
        double mean = total / (double) recorded / NANOS_PER_MILLI;
        return String.format(Locale.ROOT,
                "frameTimes %s: frames=%d seconds=%.2f fps=%.1f mean=%.2f median=%.2f p95=%.2f p99=%.2f max=%.2f",
                windowLabel, recorded, total / 1e9, recorded / (total / 1e9), mean,
                percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 0.99),
                sorted[sorted.length - 1] / NANOS_PER_MILLI);
    }

    private static double percentile(long[] sorted, double fraction) {
        int index = (int) Math.min(sorted.length - 1, Math.round(fraction * (sorted.length - 1)));
        return sorted[index] / NANOS_PER_MILLI;
    }

    private static boolean parseBoolean(String value) {
        switch (value.toLowerCase(Locale.ROOT)) {
            case "true":
            case "on":
            case "1":
                return true;
            case "false":
            case "off":
            case "0":
                return false;
            default:
                throw new IllegalArgumentException("expected true or false");
        }
    }
}
