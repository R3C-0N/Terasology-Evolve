// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu.videoSettings;

import org.terasology.engine.config.RenderingConfig;
import org.terasology.engine.rendering.nui.layers.mainMenu.settings.SettingsPalette;
import org.terasology.engine.rendering.world.viewDistance.ViewDistance;
import org.terasology.nui.Colorc;

/**
 * Graphics profiles. Each one sets every setting that changes what a frame costs, and nothing else.
 * <p>
 * The frame limit, the loading threads and the comfort settings are left alone: a profile says how much the machine can
 * draw, not how the player likes the camera. {@link #find} names the profile the current settings match, or
 * {@link #CUSTOM} when they match none.
 */
public enum Preset {
    MINIMAL("minimal", SettingsPalette.SUCCESS, medium()
            .fboScale(50).fieldOfView(80).viewDistance(ViewDistance.LEGALLY_BLIND).chunkLods(0).billboardLimit(16)
            .shadows(DynamicShadows.OFF).reflections(WaterReflection.SKY).bloom(false).lightShafts(false)
            .normalMapping(false).cloudShadows(false).animate(false)),
    LOW("low", SettingsPalette.SUCCESS, medium()
            .fboScale(75).viewDistance(ViewDistance.NEAR).chunkLods(0).billboardLimit(32)
            .shadows(DynamicShadows.OFF).reflections(WaterReflection.SKY).lightShafts(false)
            .normalMapping(false).cloudShadows(false)),
    MEDIUM("medium", SettingsPalette.ACCENT, medium()),
    HIGH("high", SettingsPalette.ACCENT, medium()
            .viewDistance(ViewDistance.FAR).chunkLods(2).billboardLimit(0)
            .shadows(DynamicShadows.ON_PCF).reflections(WaterReflection.GLOBAL).blurIntensity(1)
            .ssao(true).inscattering(true).eyeAdaptation(true)),
    ULTRA("ultra", SettingsPalette.DANGER, medium()
            .fieldOfView(90).viewDistance(ViewDistance.ULTRA).chunkLods(3).billboardLimit(0)
            .shadows(DynamicShadows.ON_PCF).reflections(WaterReflection.GLOBAL).blurIntensity(3)
            .ssao(true).inscattering(true).eyeAdaptation(true).parallaxMapping(true).outline(true)
            .volumetricFog(true).motionBlur(true)),
    CUSTOM("custom", SettingsPalette.TEXT_MUTED, null);

    private final String key;
    private final Colorc tone;
    private final Values values;

    Preset(String key, Colorc tone, Values values) {
        this.key = key;
        this.tone = tone;
        this.values = values;
    }

    /** The profile the settings match, or {@link #CUSTOM}. */
    public static Preset find(RenderingConfig config) {
        for (Preset preset : values()) {
            if (preset.values != null && preset.values.matches(config)) {
                return preset;
            }
        }
        return CUSTOM;
    }

    /** Applies the profile's settings. {@link #CUSTOM} has none and changes nothing. */
    public void apply(RenderingConfig config) {
        if (values != null) {
            values.apply(config);
        }
    }

    public String getDisplayName() {
        return "${engine:menu#opt-profile-" + key + "}";
    }

    /** Who the profile is for: an old machine, a laptop, screenshots. */
    public String getHint() {
        return "${engine:menu#opt-profile-" + key + "-hint}";
    }

    /** The colour the profile strip lights this profile with. */
    public Colorc getTone() {
        return tone;
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    /** The medium profile, which every other one starts from. */
    private static Values medium() {
        return new Values();
    }

    static WaterReflection reflectionsOf(RenderingConfig config) {
        if (config.isReflectiveWater()) {
            return WaterReflection.GLOBAL;
        }
        return config.isLocalReflections() ? WaterReflection.LOCAL : WaterReflection.SKY;
    }

    /** The settings of one profile, starting from the medium one. */
    private static final class Values {
        private int fboScale = 100;
        private float fieldOfView = 85;
        private ViewDistance viewDistance = ViewDistance.MODERATE;
        private int chunkLods = 1;
        private int billboardLimit = 64;
        private DynamicShadows shadows = DynamicShadows.ON;
        private WaterReflection reflections = WaterReflection.LOCAL;
        private boolean ssao;
        private boolean cloudShadows = true;
        private boolean normalMapping = true;
        private boolean clampLighting;
        private boolean flickeringLight = true;
        private boolean animate = true;
        private int blurIntensity;
        private boolean parallaxMapping;
        private boolean inscattering;
        private boolean outline;
        private boolean eyeAdaptation;
        private boolean bloom = true;
        private boolean lightShafts = true;
        private boolean volumetricFog;
        private boolean motionBlur;
        private boolean filmGrain;
        private boolean vignette;

        Values fboScale(int value) {
            fboScale = value;
            return this;
        }

        Values fieldOfView(float value) {
            fieldOfView = value;
            return this;
        }

        Values viewDistance(ViewDistance value) {
            viewDistance = value;
            return this;
        }

        Values chunkLods(int value) {
            chunkLods = value;
            return this;
        }

        /** Zero means no limit. */
        Values billboardLimit(int value) {
            billboardLimit = value;
            return this;
        }

        Values shadows(DynamicShadows value) {
            shadows = value;
            return this;
        }

        Values reflections(WaterReflection value) {
            reflections = value;
            return this;
        }

        Values ssao(boolean value) {
            ssao = value;
            return this;
        }

        Values cloudShadows(boolean value) {
            cloudShadows = value;
            return this;
        }

        Values normalMapping(boolean value) {
            normalMapping = value;
            return this;
        }

        /** Grass and water together. */
        Values animate(boolean value) {
            animate = value;
            return this;
        }

        Values blurIntensity(int value) {
            blurIntensity = value;
            return this;
        }

        Values parallaxMapping(boolean value) {
            parallaxMapping = value;
            return this;
        }

        Values inscattering(boolean value) {
            inscattering = value;
            return this;
        }

        Values outline(boolean value) {
            outline = value;
            return this;
        }

        Values eyeAdaptation(boolean value) {
            eyeAdaptation = value;
            return this;
        }

        Values bloom(boolean value) {
            bloom = value;
            return this;
        }

        Values lightShafts(boolean value) {
            lightShafts = value;
            return this;
        }

        Values volumetricFog(boolean value) {
            volumetricFog = value;
            return this;
        }

        Values motionBlur(boolean value) {
            motionBlur = value;
            return this;
        }

        void apply(RenderingConfig config) {
            config.setFboScale(fboScale);
            config.setFieldOfView(fieldOfView);
            config.setViewDistance(viewDistance);
            config.setChunkLods(chunkLods);
            config.setBillboardLimit(billboardLimit);
            shadows.apply(config);
            reflections.apply(config);
            config.setSsao(ssao);
            config.setCloudShadows(cloudShadows);
            config.setNormalMapping(normalMapping);
            config.setClampLighting(clampLighting);
            config.setFlickeringLight(flickeringLight);
            config.setAnimateGrass(animate);
            config.setAnimateWater(animate);
            config.setBlurIntensity(blurIntensity);
            config.setParallaxMapping(parallaxMapping);
            config.setInscattering(inscattering);
            config.setOutline(outline);
            config.setEyeAdaptation(eyeAdaptation);
            config.setBloom(bloom);
            config.setLightShafts(lightShafts);
            config.setVolumetricFog(volumetricFog);
            config.setMotionBlur(motionBlur);
            config.setFilmGrain(filmGrain);
            config.setVignette(vignette);
        }

        boolean matches(RenderingConfig config) {
            return config.getFboScale() == fboScale
                    && Float.compare(config.getFieldOfView(), fieldOfView) == 0
                    && config.getViewDistance() == viewDistance
                    && (int) config.getChunkLods() == chunkLods
                    && (int) config.getBillboardLimit() == billboardLimit
                    && DynamicShadows.find(config.isDynamicShadows(), config.isDynamicShadowsPcfFiltering()) == shadows
                    && reflectionsOf(config) == reflections
                    && config.isSsao() == ssao
                    && config.isCloudShadows() == cloudShadows
                    && config.isNormalMapping() == normalMapping
                    && config.isClampLighting() == clampLighting
                    && config.isFlickeringLight() == flickeringLight
                    && config.isAnimateGrass() == animate
                    && config.isAnimateWater() == animate
                    && config.getBlurIntensity() == blurIntensity
                    && config.isParallaxMapping() == parallaxMapping
                    && config.isInscattering() == inscattering
                    && config.isOutline() == outline
                    && config.isEyeAdaptation() == eyeAdaptation
                    && config.isBloom() == bloom
                    && config.isLightShafts() == lightShafts
                    && config.isVolumetricFog() == volumetricFog
                    && config.isMotionBlur() == motionBlur
                    && config.isFilmGrain() == filmGrain
                    && config.isVignette() == vignette;
        }
    }
}
