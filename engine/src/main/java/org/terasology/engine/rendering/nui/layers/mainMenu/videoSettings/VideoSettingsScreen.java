// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu.videoSettings;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingConfig;
import org.terasology.engine.core.Time;
import org.terasology.engine.core.subsystem.DisplayDevice;
import org.terasology.engine.core.subsystem.Resolution;
import org.terasology.engine.registry.CoreRegistry;
import org.terasology.engine.registry.In;
import org.terasology.engine.rendering.ShaderManager;
import org.terasology.engine.rendering.nui.layers.mainMenu.WaitPopup;
import org.terasology.engine.rendering.nui.layers.mainMenu.settings.BudgetBar;
import org.terasology.engine.rendering.nui.layers.mainMenu.settings.ColorBlock;
import org.terasology.engine.rendering.nui.layers.mainMenu.settings.Impact;
import org.terasology.engine.rendering.nui.layers.mainMenu.settings.SegmentGauge;
import org.terasology.engine.rendering.nui.layers.mainMenu.settings.SettingsPalette;
import org.terasology.engine.rendering.nui.layers.mainMenu.settings.SettingsRows;
import org.terasology.engine.rendering.nui.layers.mainMenu.settings.SettingsTab;
import org.terasology.engine.rendering.nui.layers.mainMenu.settings.SettingsTabScreen;
import org.terasology.engine.rendering.world.viewDistance.ViewDistance;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.nui.Colorc;
import org.terasology.nui.UIWidget;
import org.terasology.nui.databinding.Binding;
import org.terasology.nui.databinding.ReadOnlyBinding;
import org.terasology.nui.layouts.ColumnLayout;
import org.terasology.nui.layouts.RowLayout;
import org.terasology.nui.widgets.UIDropdownScrollable;
import org.terasology.nui.widgets.UISpace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Graphics tab of the options: a graphics profile, an estimated frame budget, and every rendering setting grouped by
 * what it changes, most costly first, each with what it costs a frame.
 */
public class VideoSettingsScreen extends SettingsTabScreen {

    public static final ResourceUrn ASSET_URI = SettingsTab.VIDEO.getAssetUri();

    private static final Logger logger = LoggerFactory.getLogger(VideoSettingsScreen.class);
    private static final long RESOLUTION_REVERT_TIME_MS = 15000;

    /** Frame limit slider positions that stand for vertical sync and for no limit at all. */
    private static final int LIMIT_VSYNC = 30;
    private static final int LIMIT_NONE = 250;
    private static final int LIMIT_STEP = 10;
    private static final int[] BILLBOARD_LIMITS = {16, 32, 64, 128, 0};
    private static final int[] CHUNK_THREADS = {0, 1, 2, 4, 8};
    private static final List<Integer> UI_SCALES = Arrays.asList(75, 100, 125, 150);
    private static final int MAX_CHUNK_LODS = 3;
    private static final int MAX_BLUR = 3;
    private static final double BUDGET_OVER_MILLIS = 26;

    private static final String ESTIMATED = "${engine:menu#opt-gain-estimated}";
    private static final String NOISE = "${engine:menu#opt-gain-noise}";
    private static final String NEGLIGIBLE = "${engine:menu#opt-gain-negligible}";
    private static final String FREE = "${engine:menu#opt-gain-free}";
    private static final Set<String> WITHOUT_HELP = Set.of("cloud", "flicker", "clamp", "animate", "bloom", "shafts", "fog",
            "motion", "grain", "vignette", "camera", "bob", "menu-anim", "ui-scale", "shot-size", "shot-format");

    @In
    private Config config;

    @In
    private DisplayDevice displayDevice;

    @In
    private Time time;

    private RenderingConfig rendering;
    private SettingsRows rows;
    /** Set when the player steps onto the custom profile, which no change of setting selects by itself. */
    private boolean customChosen;

    @Override
    protected SettingsTab getTab() {
        return SettingsTab.VIDEO;
    }

    @Override
    public void initialise() {
        initialiseTabs();
        rendering = config.getRendering();
        rows = rows();

        ColumnLayout overview = find("overview", ColumnLayout.class);
        if (overview != null) {
            overview.addWidget(profilePanel());
            overview.addWidget(budgetPanel());
            overview.addWidget(legend());
        }

        int settings = 0;
        ColumnLayout sectionColumn = find("sections", ColumnLayout.class);
        if (sectionColumn != null) {
            for (Section section : sections()) {
                sectionColumn.addWidget(rows.section(section.title(), section.note(), section.rows));
                settings += section.rows.size();
            }
        }
        setSettingCount(settings);
    }

    @Override
    public void onClosed() {
        super.onClosed();
        logger.info("Video Settings: {}", config.renderConfigAsJson(rendering)); //NOPMD
        // Several settings are compiled into the shaders.
        CoreRegistry.get(ShaderManager.class).recompileAllShaders();
    }

    private List<Section> sections() {
        Section resolution = new Section("resolution")
                .add(slider("fbo", Impact.MAJOR, true, gain("fbo"), 25, 100, 5,
                        () -> (float) rendering.getFboScale(), value -> rendering.setFboScale(Math.round(value)),
                        rows::percent))
                .add(slider("fov", Impact.MODERATE, false, ESTIMATED, 60, 110, 1,
                        rendering::getFieldOfView, rendering::setFieldOfView,
                        value -> rows.format("${engine:menu#opt-degrees}", Math.round(value))))
                .add(slider("limit", Impact.NONE, true, gain("limit"), LIMIT_VSYNC, LIMIT_NONE, LIMIT_STEP,
                        this::getFrameLimitPosition, this::setFrameLimitPosition, this::frameLimitLabel))
                .add(choice("display-mode", Impact.NONE, false, FREE,
                        Arrays.asList(DisplayModeSetting.WINDOWED, DisplayModeSetting.WINDOWED_FULLSCREEN,
                                DisplayModeSetting.FULLSCREEN),
                        VideoSettingsScreen::displayModeName, displayDevice::getDisplayModeSetting,
                        displayDevice::setDisplayModeSetting))
                .add(rows.row(name("resolution"), help("resolution"), resolutionDropdown(), Impact.MAJOR, false, ESTIMATED));

        Section world = new Section("world")
                .add(slider("dist", Impact.MAJOR, false, ESTIMATED, 0, ViewDistance.values().length - 1, 1,
                        () -> (float) rendering.getViewDistance().getIndex(),
                        value -> rendering.setViewDistance(ViewDistance.forIndex(Math.round(value))),
                        value -> rows.format("${engine:menu#opt-chunks}",
                                ViewDistance.forIndex(Math.round(value)).getChunkDistance().x())))
                .add(slider("lod", Impact.MODERATE, false, ESTIMATED, 0, MAX_CHUNK_LODS, 1,
                        () -> Math.min(MAX_CHUNK_LODS, rendering.getChunkLods()), rendering::setChunkLods,
                        value -> rows.format("${engine:menu#opt-lod-levels}", Math.round(value))))
                .add(slider("billboard", Impact.MODERATE, false, ESTIMATED, 0, BILLBOARD_LIMITS.length - 1, 1,
                        () -> (float) closest(BILLBOARD_LIMITS, (int) rendering.getBillboardLimit()),
                        value -> rendering.setBillboardLimit(BILLBOARD_LIMITS[index(value, BILLBOARD_LIMITS)]),
                        this::billboardLabel))
                .add(slider("threads", Impact.LOW, false, gain("threads"), 0, CHUNK_THREADS.length - 1, 1,
                        () -> (float) closest(CHUNK_THREADS, rendering.getChunkThreads()),
                        value -> rendering.setChunkThreads(CHUNK_THREADS[index(value, CHUNK_THREADS)]),
                        this::threadsLabel));

        Section shadows = new Section("shadows")
                .add(choice("shadows", Impact.MAJOR, true, gain("shadows"), Arrays.asList(DynamicShadows.values()),
                        VideoSettingsScreen::shadowsName,
                        () -> DynamicShadows.find(rendering.isDynamicShadows(), rendering.isDynamicShadowsPcfFiltering()),
                        mode -> mode.apply(rendering)))
                .add(toggle("ssao", SettingsRows.FEMININE, Impact.MAJOR, false, ESTIMATED,
                        rendering::isSsao, rendering::setSsao))
                .add(toggle("normals", SettingsRows.MASCULINE, Impact.NONE, true, NOISE,
                        rendering::isNormalMapping, rendering::setNormalMapping))
                .add(toggle("cloud", SettingsRows.FEMININE_PLURAL, Impact.NONE, true, NOISE,
                        rendering::isCloudShadows, rendering::setCloudShadows))
                .add(toggle("flicker", SettingsRows.FEMININE, Impact.NONE, false, NEGLIGIBLE,
                        rendering::isFlickeringLight, rendering::setFlickeringLight))
                .add(toggle("clamp", SettingsRows.MASCULINE, Impact.NONE, false, NEGLIGIBLE,
                        rendering::isClampLighting, rendering::setClampLighting));

        Section water = new Section("water")
                .add(choice("reflect", Impact.MAJOR, true, gain("reflect"),
                        Arrays.asList(WaterReflection.SKY, WaterReflection.LOCAL, WaterReflection.GLOBAL),
                        VideoSettingsScreen::reflectionsName, () -> Preset.reflectionsOf(rendering),
                        mode -> mode.apply(rendering)))
                .add(toggle("animate", SettingsRows.FEMININE, Impact.NONE, true, NOISE,
                        () -> rendering.isAnimateGrass() && rendering.isAnimateWater(),
                        enabled -> {
                            rendering.setAnimateGrass(enabled);
                            rendering.setAnimateWater(enabled);
                        }));

        Section screen = new Section("screen")
                .add(slider("dof", Impact.MODERATE, false, ESTIMATED, 0, MAX_BLUR, 1,
                        () -> (float) Math.min(MAX_BLUR, rendering.getBlurIntensity()),
                        value -> rendering.setBlurIntensity(Math.round(value)),
                        value -> rows.translate("${engine:menu#opt-dof-" + Math.round(value) + "}")))
                .add(toggle("parallax", SettingsRows.FEMININE, Impact.MODERATE, false, ESTIMATED,
                        rendering::isParallaxMapping, rendering::setParallaxMapping))
                .add(toggle("inscatter", SettingsRows.FEMININE, Impact.MODERATE, false, ESTIMATED,
                        rendering::isInscattering, rendering::setInscattering))
                .add(toggle("outline", SettingsRows.MASCULINE_PLURAL, Impact.LOW, false, ESTIMATED,
                        rendering::isOutline, rendering::setOutline))
                .add(toggle("eye", SettingsRows.FEMININE, Impact.LOW, false, ESTIMATED,
                        rendering::isEyeAdaptation, rendering::setEyeAdaptation))
                .add(toggle("bloom", SettingsRows.MASCULINE, Impact.NONE, true, NOISE,
                        rendering::isBloom, rendering::setBloom))
                .add(toggle("shafts", SettingsRows.MASCULINE_PLURAL, Impact.NONE, true, NOISE,
                        rendering::isLightShafts, rendering::setLightShafts))
                .add(toggle("fog", SettingsRows.MASCULINE, Impact.NONE, false, NEGLIGIBLE,
                        rendering::isVolumetricFog, rendering::setVolumetricFog))
                .add(toggle("motion", SettingsRows.MASCULINE, Impact.NONE, false, NEGLIGIBLE,
                        rendering::isMotionBlur, rendering::setMotionBlur))
                .add(toggle("grain", SettingsRows.MASCULINE, Impact.NONE, false, NEGLIGIBLE,
                        rendering::isFilmGrain, rendering::setFilmGrain))
                .add(toggle("vignette", SettingsRows.MASCULINE, Impact.NONE, false, NEGLIGIBLE,
                        rendering::isVignette, rendering::setVignette));

        Section comfort = new Section("comfort")
                .add(choice("camera", Impact.NONE, true, FREE, Arrays.asList(CameraSetting.values()), CameraSetting::toString,
                        () -> rendering.getCameraSettings().getCameraSetting(),
                        setting -> rendering.getCameraSettings().setCameraSetting(setting)))
                .add(toggle("bob", SettingsRows.MASCULINE, Impact.NONE, true, FREE,
                        rendering::isCameraBobbing, rendering::setCameraBobbing))
                .add(toggle("menu-anim", SettingsRows.FEMININE_PLURAL, Impact.NONE, true, FREE,
                        rendering::isAnimatedMenu, rendering::setAnimatedMenu))
                .add(choice("ui-scale", Impact.NONE, true, FREE, UI_SCALES, scale -> rows.percent(scale),
                        rendering::getUiScale, rendering::setUiScale))
                .add(choice("shot-size", Impact.NONE, true, FREE,
                        Arrays.asList(ScreenshotSize.HALF_SIZE, ScreenshotSize.NORMAL_SIZE, ScreenshotSize.DOUBLE_SIZE,
                                ScreenshotSize.UHD_1),
                        VideoSettingsScreen::screenshotSizeName, rendering::getScreenshotSize, rendering::setScreenshotSize))
                .add(choice("shot-format", Impact.NONE, true, FREE, Arrays.asList("png", "jpg"),
                        format -> format.toUpperCase(Locale.ROOT), this::screenshotFormat, rendering::setScreenshotFormat));

        return Arrays.asList(resolution, world, shadows, water, screen, comfort);
    }

    // --- rows ----------------------------------------------------------------------------------------------------------

    private UIWidget slider(String id, Impact impact, boolean measured, String gain, float minimum, float maximum, float step,
                            Supplier<Float> get, Consumer<Float> set, Function<Float, String> readout) {
        Binding<Float> value = SettingsRows.bindFloat(get, edit(set));
        return rows.row(name(id), help(id), rows.slider(minimum, maximum, step, value, readout), impact, measured, gain);
    }

    private <T> UIWidget choice(String id, Impact impact, boolean measured, String gain, List<T> options,
                                Function<T, String> optionName, Supplier<T> current, Consumer<T> choose) {
        return rows.row(name(id), help(id), rows.choice(options, optionName, current, edit(choose)), impact, measured, gain);
    }

    private UIWidget toggle(String id, String agreement, Impact impact, boolean measured, String gain,
                            BooleanSupplier get, Consumer<Boolean> set) {
        Binding<Boolean> checked = SettingsRows.bindBoolean(get, edit(set));
        return rows.row(name(id), help(id), rows.toggle(agreement, checked), impact, measured, gain);
    }

    /** Any change of setting ends a choice of the custom profile: the profile shown is the one the settings match. */
    private <T> Consumer<T> edit(Consumer<T> set) {
        return value -> {
            set.accept(value);
            customChosen = false;
        };
    }

    private static String name(String id) {
        return "${engine:menu#opt-" + id + "}";
    }

    private static String help(String id) {
        return WITHOUT_HELP.contains(id) ? null : "${engine:menu#opt-" + id + "-help}";
    }

    private static String gain(String id) {
        return "${engine:menu#opt-" + id + "-gain}";
    }

    private UIWidget resolutionDropdown() {
        UIDropdownScrollable<Resolution> dropdown = new UIDropdownScrollable<>();
        dropdown.setOptions(displayDevice.getResolutions());
        dropdown.bindSelection(new Binding<Resolution>() {
            @Override
            public Resolution get() {
                return displayDevice.getResolution();
            }

            @Override
            public void set(Resolution value) {
                onResolutionChange(value);
            }
        });
        return dropdown;
    }

    private float getFrameLimitPosition() {
        if (rendering.isVSync()) {
            return LIMIT_VSYNC;
        }
        int limit = rendering.getFrameLimit();
        if (limit <= 0) {
            return LIMIT_NONE;
        }
        return Math.max(LIMIT_VSYNC + LIMIT_STEP, Math.min(LIMIT_NONE - LIMIT_STEP, limit));
    }

    private void setFrameLimitPosition(float position) {
        int limit = Math.round(position);
        boolean vSync = limit <= LIMIT_VSYNC;
        rendering.setVSync(vSync);
        rendering.setFrameLimit(vSync || limit >= LIMIT_NONE ? -1 : limit);
        if (!displayDevice.isHeadless()) {
            // The window reads vertical sync once, when it is created: applying it now takes the swap interval itself.
            GLFW.glfwSwapInterval(vSync ? 1 : 0);
        }
    }

    private String frameLimitLabel(float position) {
        int limit = Math.round(position);
        if (limit <= LIMIT_VSYNC) {
            return rows.translate("${engine:menu#opt-limit-vsync}");
        }
        if (limit >= LIMIT_NONE) {
            return rows.translate("${engine:menu#opt-limit-none}");
        }
        return rows.format("${engine:menu#opt-limit-fps}", limit);
    }

    private String billboardLabel(float position) {
        int limit = BILLBOARD_LIMITS[index(position, BILLBOARD_LIMITS)];
        return limit == 0 ? rows.translate("${engine:menu#opt-billboard-none}") : rows.format("${engine:menu#opt-chunks}", limit);
    }

    private String threadsLabel(float position) {
        int threads = CHUNK_THREADS[index(position, CHUNK_THREADS)];
        if (threads == 0) {
            return rows.translate("${engine:menu#opt-threads-auto}");
        }
        return threads == 1 ? rows.translate("${engine:menu#opt-threads-one}") : rows.format("${engine:menu#opt-threads-many}", threads);
    }

    private String screenshotFormat() {
        String format = rendering.getScreenshotFormat();
        return format == null ? null : format.toLowerCase(Locale.ROOT);
    }

    private static String displayModeName(DisplayModeSetting mode) {
        switch (mode) {
            case FULLSCREEN:
                return "${engine:menu#opt-display-fullscreen}";
            case WINDOWED_FULLSCREEN:
                return "${engine:menu#opt-display-borderless}";
            default:
                return "${engine:menu#opt-display-windowed}";
        }
    }

    private static String shadowsName(DynamicShadows mode) {
        switch (mode) {
            case ON:
                return "${engine:menu#opt-shadows-on}";
            case ON_PCF:
                return "${engine:menu#opt-shadows-pcf}";
            default:
                return "${engine:menu#opt-shadows-off}";
        }
    }

    private static String reflectionsName(WaterReflection mode) {
        switch (mode) {
            case LOCAL:
                return "${engine:menu#opt-reflect-local}";
            case GLOBAL:
                return "${engine:menu#opt-reflect-global}";
            default:
                return "${engine:menu#opt-reflect-none}";
        }
    }

    private static String screenshotSizeName(ScreenshotSize size) {
        switch (size) {
            case HALF_SIZE:
                return "${engine:menu#opt-shot-half}";
            case DOUBLE_SIZE:
                return "${engine:menu#opt-shot-double}";
            case UHD_1:
                return "${engine:menu#opt-shot-4k}";
            default:
                return "${engine:menu#opt-shot-screen}";
        }
    }

    /** The position of a slider over {@code values}, clamped to the array. */
    private static int index(float position, int[] values) {
        return Math.max(0, Math.min(values.length - 1, Math.round(position)));
    }

    /** The index of the value nearest to {@code target}. */
    private static int closest(int[] values, int target) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - target) < Math.abs(values[best] - target)) {
                best = i;
            }
        }
        return best;
    }

    // --- profile, budget and legend ----------------------------------------------------------------------------------

    private UIWidget profilePanel() {
        ColumnLayout name = rows.column(0);
        name.setFamily("profile-slot");
        name.addWidget(rows.label(() -> rows.translate(currentProfile().getDisplayName()), "profile-name"));
        name.addWidget(rows.label(() -> rows.translate(currentProfile().getHint()), "profile-hint"));

        RowLayout picker = rows.line(10);
        SettingsRows.fit(picker, rows.button("«", "btn-step", () -> stepProfile(-1)));
        SettingsRows.fill(picker, name);
        SettingsRows.fit(picker, rows.button("»", "btn-step", () -> stepProfile(1)));

        RowLayout actions = rows.line(10);
        SettingsRows.fit(actions, rows.button("${engine:menu#opt-profile-cut}", "btn-ghost", this::cutMeasuredCosts));
        SettingsRows.fit(actions, rows.button("${engine:menu#opt-profile-reset}", "btn-ghost", () -> chooseProfile(Preset.MEDIUM)));
        SettingsRows.fill(actions, new UISpace());

        ColumnLayout body = rows.column(10);
        body.addWidget(rows.centered(rows.label("${engine:menu#opt-profile-title}", "panel-title-small")));
        body.addWidget(picker);
        body.addWidget(new SegmentGauge(Preset.values().length, 0, 12, 4, this::profileSegment));
        body.addWidget(rows.label("${engine:menu#opt-profile-note}", "section-note-left"));
        body.addWidget(actions);
        return rows.box("panel-slim", body);
    }

    private Preset currentProfile() {
        return customChosen ? Preset.CUSTOM : Preset.find(rendering);
    }

    private void stepProfile(int step) {
        Preset[] profiles = Preset.values();
        chooseProfile(profiles[Math.floorMod(currentProfile().ordinal() + step, profiles.length)]);
    }

    private void chooseProfile(Preset profile) {
        profile.apply(rendering);
        customChosen = profile == Preset.CUSTOM;
    }

    /** Lights the profiles up to the current one, each in its own tone; the custom profile lights only itself. */
    private Colorc profileSegment(int index) {
        Preset current = currentProfile();
        if (current == Preset.CUSTOM) {
            return index == current.ordinal() ? SettingsPalette.TEXT_SECONDARY : SettingsPalette.OFF;
        }
        return index <= current.ordinal() ? Preset.values()[index].getTone() : SettingsPalette.OFF;
    }

    /** Turns off every setting whose cost was measured, and brings the render scale down to three quarters. */
    private void cutMeasuredCosts() {
        WaterReflection.SKY.apply(rendering);
        DynamicShadows.OFF.apply(rendering);
        rendering.setBloom(false);
        rendering.setLightShafts(false);
        rendering.setNormalMapping(false);
        rendering.setFboScale(75);
        customChosen = false;
    }

    private UIWidget budgetPanel() {
        ColumnLayout frameTime = rows.column(0);
        frameTime.addWidget(rows.label("${engine:menu#opt-budget-frame-time}", "budget-caps"));
        frameTime.addWidget(rows.label(() -> millis(FrameBudget.frameMillis(rendering)), "budget-number"));
        ColumnLayout frameRate = rows.column(0);
        frameRate.addWidget(rows.label("${engine:menu#opt-budget-fps}", "budget-caps-right"));
        frameRate.addWidget(rows.label(() -> String.format(Locale.ROOT, "%.0f", FrameBudget.framesPerSecond(rendering)),
                "budget-number-accent"));
        RowLayout numbers = rows.line(16);
        SettingsRows.fill(numbers, frameTime);
        SettingsRows.fill(numbers, frameRate);

        ColumnLayout body = rows.column(8);
        body.addWidget(rows.centered(rows.label("${engine:menu#opt-budget-title}", "panel-title-small")));
        body.addWidget(numbers);
        body.addWidget(new BudgetBar(() -> FrameBudget.frameMillis(rendering) / FrameBudget.FULL_BAR_MILLIS, this::budgetTone,
                FrameBudget.TARGET_MILLIS / FrameBudget.FULL_BAR_MILLIS));
        body.addWidget(rows.label("${engine:menu#opt-budget-mark}", "setting-help"));
        body.addWidget(ColorBlock.of(1, 1, SettingsPalette.HAIRLINE));
        body.addWidget(costRow("${engine:menu#opt-fbo}", () -> rows.percent(rendering.getFboScale()),
                () -> rendering.getFboScale() < 100 ? SettingsPalette.SUCCESS : SettingsPalette.DANGER));
        body.addWidget(costRow("${engine:menu#opt-reflect}", () -> millis(FrameBudget.reflectionMillis(rendering)),
                () -> tone(FrameBudget.reflectionMillis(rendering), 2)));
        body.addWidget(costRow("${engine:menu#opt-section-shadows}", () -> millis(FrameBudget.shadowMillis(rendering)),
                () -> tone(FrameBudget.shadowMillis(rendering), 2)));
        body.addWidget(costRow("${engine:menu#opt-dist}", () -> millis(FrameBudget.viewDistanceMillis(rendering)),
                () -> tone(FrameBudget.viewDistanceMillis(rendering), 4)));
        return rows.box("panel-slim", body);
    }

    private UIWidget costRow(String name, Supplier<String> cost, Supplier<Colorc> tone) {
        RowLayout line = rows.line(10);
        SettingsRows.fit(line, new ColorBlock(8, 16, tone, true));
        SettingsRows.fill(line, rows.label(name, "budget-cost"));
        SettingsRows.fit(line, rows.label(cost, "budget-cost-value"));
        return line;
    }

    private Colorc budgetTone() {
        double millis = FrameBudget.frameMillis(rendering);
        if (millis > BUDGET_OVER_MILLIS) {
            return SettingsPalette.DANGER;
        }
        return millis > FrameBudget.TARGET_MILLIS ? SettingsPalette.ACCENT : SettingsPalette.SUCCESS;
    }

    private static Colorc tone(double millis, double high) {
        if (millis > high) {
            return SettingsPalette.DANGER;
        }
        return millis > 0 ? SettingsPalette.ACCENT : SettingsPalette.SUCCESS;
    }

    private String millis(double value) {
        String number = String.format(Locale.ROOT, "%.1f", value)
                .replace(".", rows.translate("${engine:menu#opt-decimal-separator}"));
        return rows.format("${engine:menu#opt-ms}", number);
    }

    private UIWidget legend() {
        ColumnLayout body = rows.column(4);
        body.addWidget(rows.label("${engine:menu#opt-legend-title}", "budget-caps"));
        for (Impact impact : new Impact[]{Impact.MAJOR, Impact.MODERATE, Impact.LOW, Impact.NONE}) {
            RowLayout line = rows.line(10);
            SettingsRows.fit(line, new SegmentGauge(3, 8, 14, 2, impact::pip));
            SettingsRows.fill(line, rows.label(impact.getLegend(), "legend-label"));
            body.addWidget(line);
        }
        return rows.box("legend", body);
    }

    // --- resolution --------------------------------------------------------------------------------------------------

    private void onFullScreenResolutionChange(Resolution oldResolution) {
        Callable<Resolution> revertOperation = () -> {
            Thread.sleep(RESOLUTION_REVERT_TIME_MS);
            return oldResolution;
        };

        @SuppressWarnings("unchecked")
        WaitPopup<Resolution> popup = getManager().pushScreen(WaitPopup.ASSET_URI, WaitPopup.class);

        popup.startOperation(revertOperation, true);
        popup.onSuccess(resolution -> displayDevice.setResolution(resolution));
        popup.setTitleText(rows.translate("${engine:menu#video-resolution-popup-title}"));
        popup.setCancelText(rows.translate("${engine:menu#video-resolution-popup-cancel}"));

        long revertAtMs = time.getGameTimeInMs() + RESOLUTION_REVERT_TIME_MS;
        String message = rows.translate("${engine:menu#video-resolution-popup-message}");

        popup.bindMessageText(new ReadOnlyBinding<String>() {
            @Override
            public String get() {
                long remaining = TimeUnit.MILLISECONDS.toSeconds(revertAtMs - time.getGameTimeInMs());
                return message + ": " + remaining + "s";
            }
        });
    }

    private void onResolutionChange(Resolution newResolution) {
        Resolution oldResolution = displayDevice.getResolution();
        displayDevice.setResolution(newResolution);
        if (DisplayModeSetting.FULLSCREEN == displayDevice.getDisplayModeSetting()) {
            onFullScreenResolutionChange(oldResolution);
        }
    }

    /** A group of rows under one title. */
    private static final class Section {
        private final String key;
        private final List<UIWidget> rows = new ArrayList<>();

        Section(String key) {
            this.key = key;
        }

        Section add(UIWidget row) {
            rows.add(row);
            return this;
        }

        String title() {
            return "${engine:menu#opt-section-" + key + "}";
        }

        String note() {
            return "${engine:menu#opt-section-" + key + "-note}";
        }
    }
}
