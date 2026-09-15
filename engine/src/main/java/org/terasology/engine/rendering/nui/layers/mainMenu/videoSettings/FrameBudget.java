// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu.videoSettings;

import org.terasology.engine.config.RenderingConfig;
import org.terasology.engine.rendering.world.viewDistance.ViewDistance;

/**
 * Estimates what a frame costs with given rendering settings, in milliseconds: the frame budget of the options.
 * <p>
 * The costs come from a settings sweep on the reference machine, an Intel UHD 620 taking 30.5 ms a frame with everything
 * on at full scale, standing still. Reflections, shadows, render scale, bloom, light shafts, normal mapping, cloud shadows
 * and animation were measured there; the other costs are estimates of the same order. Per-pixel work scales with the
 * render scale, world work does not, and both grow a little with the field of view. The estimate compares settings with
 * each other; it does not measure the machine it runs on.
 */
public final class FrameBudget {
    /** 50 frames per second. */
    public static final double TARGET_MILLIS = 20;
    /** The frame time a full budget bar stands for. */
    public static final double FULL_BAR_MILLIS = 40;

    private static final double PIXEL_BASE_MILLIS = 8.8;
    private static final double WORLD_BASE_MILLIS = 10.3;
    private static final double[] BLUR_MILLIS = {0, 2, 2.7, 3.4};
    private static final double LEVEL_OF_DETAIL_MILLIS = 1.1;
    private static final double CHUNK_MILLIS = 0.55;
    private static final int CHUNKS_WITHIN_BASE = 8;
    private static final double REFERENCE_FIELD_OF_VIEW = 85;
    private static final double VSYNC_RATE = 60;

    private FrameBudget() {
    }

    public static double frameMillis(RenderingConfig config) {
        double pixel = PIXEL_BASE_MILLIS + reflectionMillis(config) + shadowMillis(config)
                + cost(config.isSsao(), 3) + cost(config.isParallaxMapping(), 1.5)
                + cost(config.isInscattering(), 1.2) + cost(config.isOutline(), 1)
                + cost(config.isEyeAdaptation(), 0.8) + cost(config.isBloom(), 0.3)
                + cost(config.isLightShafts(), 0.3) + cost(config.isNormalMapping(), 0.3)
                + cost(config.isCloudShadows(), 0.3) + cost(config.isAnimateGrass() || config.isAnimateWater(), 0.1)
                + cost(config.isVolumetricFog(), 0.2) + cost(config.isMotionBlur(), 0.2)
                + cost(config.isFilmGrain(), 0.1) + cost(config.isVignette(), 0.1)
                + cost(config.isFlickeringLight(), 0.1) + cost(config.isClampLighting(), 0.1)
                + BLUR_MILLIS[Math.max(0, Math.min(BLUR_MILLIS.length - 1, config.getBlurIntensity()))];
        double world = WORLD_BASE_MILLIS + viewDistanceMillis(config)
                + config.getChunkLods() * LEVEL_OF_DETAIL_MILLIS + billboardMillis(config);
        double fieldOfView = config.getFieldOfView() - REFERENCE_FIELD_OF_VIEW;
        pixel *= 1 + fieldOfView / 300;
        world *= 1 + fieldOfView / 400;
        return pixel * Math.pow(config.getFboScale() / 100.0, 1.1) + world;
    }

    /** The frame rate the estimate allows, capped by vertical sync or by the frame limit. */
    public static double framesPerSecond(RenderingConfig config) {
        double cap = Double.POSITIVE_INFINITY;
        if (config.isVSync()) {
            cap = VSYNC_RATE;
        } else if (config.getFrameLimit() > 0) {
            cap = config.getFrameLimit();
        }
        return Math.min(1000 / frameMillis(config), cap);
    }

    public static double reflectionMillis(RenderingConfig config) {
        if (config.isReflectiveWater()) {
            return 4.1;
        }
        return config.isLocalReflections() ? 1.6 : 0;
    }

    public static double shadowMillis(RenderingConfig config) {
        if (!config.isDynamicShadows()) {
            return 0;
        }
        return config.isDynamicShadowsPcfFiltering() ? 4.8 : 4.2;
    }

    public static double viewDistanceMillis(RenderingConfig config) {
        ViewDistance distance = config.getViewDistance();
        if (distance == null) {
            return 0;
        }
        return Math.max(0, distance.getChunkDistance().x() - CHUNKS_WITHIN_BASE) * CHUNK_MILLIS;
    }

    private static double billboardMillis(RenderingConfig config) {
        int limit = (int) config.getBillboardLimit();
        if (limit <= 0 || limit > 128) {
            return 1.2;
        }
        if (limit <= 16) {
            return 0.2;
        }
        if (limit <= 32) {
            return 0.35;
        }
        return limit <= 64 ? 0.6 : 0.9;
    }

    private static double cost(boolean enabled, double millis) {
        return enabled ? millis : 0;
    }
}
