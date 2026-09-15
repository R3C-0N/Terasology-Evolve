// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu.inputSettings;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.terasology.engine.config.BindsConfig;
import org.terasology.engine.config.ControllerConfig.ControllerInfo;
import org.terasology.engine.config.facade.InputDeviceConfiguration;
import org.terasology.engine.core.SimpleUri;
import org.terasology.engine.core.module.ModuleManager;
import org.terasology.engine.core.subsystem.config.BindsManager;
import org.terasology.engine.i18n.TranslationSystem;
import org.terasology.engine.input.BindButtonEvent;
import org.terasology.engine.input.InputSystem;
import org.terasology.engine.input.RegisterBindButton;
import org.terasology.engine.input.internal.BindCommands;
import org.terasology.engine.registry.In;
import org.terasology.engine.rendering.nui.layers.mainMenu.settings.SettingsRows;
import org.terasology.engine.rendering.nui.layers.mainMenu.settings.SettingsTab;
import org.terasology.engine.rendering.nui.layers.mainMenu.settings.SettingsTabScreen;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.module.Module;
import org.terasology.gestalt.module.ModuleEnvironment;
import org.terasology.gestalt.module.dependencyresolution.DependencyResolver;
import org.terasology.gestalt.module.dependencyresolution.ResolutionResult;
import org.terasology.gestalt.module.predicates.FromModule;
import org.terasology.gestalt.naming.Name;
import org.terasology.input.Input;
import org.terasology.input.InputCategory;
import org.terasology.input.InputType;
import org.terasology.input.Keyboard.KeyId;
import org.terasology.nui.TabbingManager;
import org.terasology.nui.UIWidget;
import org.terasology.nui.WidgetUtil;
import org.terasology.nui.databinding.BindHelper;
import org.terasology.nui.databinding.ReadOnlyBinding;
import org.terasology.nui.layouts.ColumnLayout;
import org.terasology.nui.layouts.RowLayout;
import org.terasology.nui.layouts.RowLayoutHint;
import org.terasology.nui.widgets.UIButton;
import org.terasology.nui.widgets.UISlider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The Controls tab of the options: keyboard layouts, the mouse, every input binding by category, and the controllers.
 */
public class InputSettingsScreen extends SettingsTabScreen {

    public static final ResourceUrn ASSET_URI = SettingsTab.INPUT.getAssetUri();
    private static final int PRIMARY_BIND_INDEX = 0;
    private static final int SECONDARY_BIND_INDEX = 1;

    @In
    private InputDeviceConfiguration inputDeviceConfiguration;

    @In
    private BindsManager bindsManager;

    @In
    private ModuleManager moduleManager;

    @In
    private InputSystem inputSystem;

    @In
    private TranslationSystem translationSystem;

    private SettingsRows rows;
    private int settingCount;
    private final Set<SimpleUri> shownBinds = Sets.newHashSet();

    @Override
    protected SettingsTab getTab() {
        return SettingsTab.INPUT;
    }

    @Override
    public void initialise() {
        initialiseTabs();
        rows = rows();
        ColumnLayout sections = find("sections", ColumnLayout.class);

        List<UIWidget> layouts = Arrays.asList(
                layoutRow("AZERTY", () -> BindCommands.AZERTY.forEach(this::setPrimaryBind)),
                layoutRow("DVORAK", () -> BindCommands.DVORAK.forEach(this::setPrimaryBind)),
                layoutRow("NEO", () -> BindCommands.NEO.forEach(this::setPrimaryBind)));

        UISlider mouseSensitivity = new UISlider("mouseSensitivity");
        mouseSensitivity.bindValue(BindHelper.bindBeanProperty("mouseSensitivity", inputDeviceConfiguration, Float.TYPE));
        mouseSensitivity.setIncrement(0.025f);
        mouseSensitivity.setPrecision(3);
        List<UIWidget> mouse = Arrays.asList(
                rows.row("${engine:menu#mouse-sensitivity}", null, mouseSensitivity),
                rows.row("${engine:menu#invert-mouse}", null, rows.toggle(SettingsRows.MASCULINE,
                        BindHelper.bindBeanProperty("mouseYAxisInverted", inputDeviceConfiguration, Boolean.TYPE))));

        Map<String, InputCategory> inputCategories = Maps.newHashMap();
        Map<SimpleUri, RegisterBindButton> inputsById = Maps.newHashMap();
        DependencyResolver resolver = new DependencyResolver(moduleManager.getRegistry());
        for (Name moduleId : moduleManager.getRegistry().getModuleIds()) {
            Module module = moduleManager.getRegistry().getLatestModuleVersion(moduleId);
            ResolutionResult result = resolver.resolve(moduleId);
            if (result.isSuccess()) {
                try (ModuleEnvironment environment = moduleManager.loadEnvironment(result.getModules(), false)) {
                    for (Class<?> holdingType : environment.getTypesAnnotatedWith(InputCategory.class,
                            new FromModule(environment, moduleId))) {
                        InputCategory inputCategory = holdingType.getAnnotation(InputCategory.class);
                        inputCategories.put(module.getId() + ":" + inputCategory.id(), inputCategory);
                    }
                    for (Class<?> bindEvent : environment.getTypesAnnotatedWith(RegisterBindButton.class,
                            new FromModule(environment, moduleId))) {
                        if (BindButtonEvent.class.isAssignableFrom(bindEvent)) {
                            RegisterBindButton bindRegister = bindEvent.getAnnotation(RegisterBindButton.class);
                            inputsById.put(new SimpleUri(module.getId(), bindRegister.id()), bindRegister);
                            // Categories annotate packages, and the environment's type index lists no package-info
                            // class: asked for types alone, it found no category and no binding was ever shown.
                            Package bindPackage = bindEvent.getPackage();
                            InputCategory packageCategory = bindPackage == null ? null : bindPackage.getAnnotation(InputCategory.class);
                            if (packageCategory != null) {
                                inputCategories.putIfAbsent(module.getId() + ":" + packageCategory.id(), packageCategory);
                            }
                        }
                    }
                }
            }
        }

        if (sections != null) {
            addSection(sections, "${engine:menu#opt-section-layouts}", "${engine:menu#opt-section-layouts-note}", layouts);
            addSection(sections, "${engine:menu#category-mouse}", null, mouse);
            addInputSection(inputCategories.remove("engine:movement"), sections, inputsById);
            addInputSection(inputCategories.remove("engine:interaction"), sections, inputsById);
            addInputSection(inputCategories.remove("engine:inventory"), sections, inputsById);
            addInputSection(inputCategories.remove("engine:general"), sections, inputsById);
            for (InputCategory category : inputCategories.values()) {
                addInputSection(category, sections, inputsById);
            }
            addUncategorisedBinds(sections, inputsById);

            List<String> controllers = inputSystem.getControllerDevice().getControllers();
            for (String name : controllers) {
                ControllerInfo cfg = inputDeviceConfiguration.getController(name);
                addControllerSection(sections, name, cfg);
            }
        }
        setSettingCount(settingCount);

        WidgetUtil.trySubscribe(this, "reset", button -> {
            inputDeviceConfiguration.reset();
            bindsManager.getBindsConfig().setBinds(bindsManager.getDefaultBindsConfig());
        });
    }

    private UIWidget layoutRow(String layout, Runnable bindLayout) {
        UIButton apply = rows.button("${engine:menu#input-settings-apply}", "choice-off", () -> {
            bindLayout.run();
            bindsManager.registerBinds();
        });
        RowLayout line = rows.line(0);
        SettingsRows.fit(line, apply);
        return rows.row(layout, null, line);
    }

    private void addSection(ColumnLayout sections, String title, String note, List<UIWidget> sectionRows) {
        sections.addWidget(rows.section(title, note, sectionRows));
        settingCount += sectionRows.size();
    }

    /**
     * Binds button to key while ensuring visual feedback on the user interface
     *
     * @param key one constant from the {@link KeyId}s.
     * @param bindId the uri for the binding, e.g. <code>engine:forwards</code>.
     */
    private void setPrimaryBind(int key, SimpleUri bindId) {
        final BindsConfig bindConfig = bindsManager.getBindsConfig();
        new InputConfigBinding(bindConfig, bindId, PRIMARY_BIND_INDEX).set(InputType.KEY.getInput(key));
    }

    private void addInputSection(InputCategory category, ColumnLayout sections,
                                 Map<SimpleUri, RegisterBindButton> inputsById) {
        if (category == null) {
            return;
        }
        List<UIWidget> sectionRows = new ArrayList<>();
        Set<SimpleUri> processedBinds = Sets.newHashSet();

        for (String bindId : category.ordering()) {
            SimpleUri bindUri = new SimpleUri(bindId);
            if (bindUri.isValid()) {
                RegisterBindButton bind = inputsById.get(new SimpleUri(bindId));
                if (bind != null) {
                    sectionRows.add(inputBindRow(bindUri, bind));
                    processedBinds.add(bindUri);
                }
            }
        }

        List<ExtensionBind> extensionBindList = Lists.newArrayList();
        for (Map.Entry<SimpleUri, RegisterBindButton> bind : inputsById.entrySet()) {
            if (bind.getValue().category().equals(category.id()) && !processedBinds.contains(bind.getKey())) {
                extensionBindList.add(new ExtensionBind(bind.getKey(), bind.getValue()));
            }
        }
        Collections.sort(extensionBindList);
        for (ExtensionBind extension : extensionBindList) {
            sectionRows.add(inputBindRow(extension.uri, extension.bind));
            processedBinds.add(extension.uri);
        }
        shownBinds.addAll(processedBinds);
        if (!sectionRows.isEmpty()) {
            addSection(sections, translationSystem.translate(category.displayName()), null, sectionRows);
        }
    }

    /** The bindings whose category no module declares, so that none of them is left out of the tab. */
    private void addUncategorisedBinds(ColumnLayout sections, Map<SimpleUri, RegisterBindButton> inputsById) {
        List<ExtensionBind> leftovers = Lists.newArrayList();
        for (Map.Entry<SimpleUri, RegisterBindButton> bind : inputsById.entrySet()) {
            if (!shownBinds.contains(bind.getKey())) {
                leftovers.add(new ExtensionBind(bind.getKey(), bind.getValue()));
            }
        }
        Collections.sort(leftovers);
        List<UIWidget> sectionRows = new ArrayList<>();
        for (ExtensionBind extension : leftovers) {
            sectionRows.add(inputBindRow(extension.uri, extension.bind));
        }
        if (!sectionRows.isEmpty()) {
            addSection(sections, "${engine:menu#opt-section-other-binds}", null, sectionRows);
        }
    }

    private void addControllerSection(ColumnLayout sections, String name, ControllerInfo info) {
        List<UIWidget> sectionRows = Arrays.asList(
                rows.row("${engine:menu#invert-x}", null,
                        rows.toggle(SettingsRows.MASCULINE, BindHelper.bindBeanProperty("invertX", info, Boolean.TYPE))),
                rows.row("${engine:menu#invert-y}", null,
                        rows.toggle(SettingsRows.MASCULINE, BindHelper.bindBeanProperty("invertY", info, Boolean.TYPE))),
                rows.row("${engine:menu#invert-z}", null,
                        rows.toggle(SettingsRows.MASCULINE, BindHelper.bindBeanProperty("invertZ", info, Boolean.TYPE))),
                rows.row("${engine:menu#movement-dead-zone}", null, deadZoneSlider("movementDeadZone", info)),
                rows.row("${engine:menu#rotation-dead-zone}", null, deadZoneSlider("rotationDeadZone", info)));
        addSection(sections, name, null, sectionRows);
    }

    private static UISlider deadZoneSlider(String property, ControllerInfo info) {
        UISlider slider = new UISlider();
        slider.setIncrement(0.01f);
        slider.setMinimum(0);
        slider.setRange(1);
        slider.setPrecision(2);
        slider.bindValue(BindHelper.bindBeanProperty(property, info, Float.TYPE));
        return slider;
    }

    private UIWidget inputBindRow(SimpleUri uri, RegisterBindButton bind) {
        List<Input> binds = bindsManager.getBindsConfig().getBinds(uri);
        RowLayout buttons = rows.line(8);
        buttons.addWidget(makeInputBindButton(uri, bind, binds, PRIMARY_BIND_INDEX), new RowLayoutHint(0.5f));
        buttons.addWidget(makeInputBindButton(uri, bind, binds, SECONDARY_BIND_INDEX), new RowLayoutHint(0.5f));
        return rows.row(translationSystem.translate(bind.description()), null, buttons);
    }

    private UIButton makeInputBindButton(SimpleUri uri, RegisterBindButton bind, List<Input> binds, int index) {
        UIButton inputBind = new UIButton();
        inputBind.bindText(new BindingText(binds, index));
        inputBind.subscribe(event -> {
            ChangeBindingPopup popup = getManager().pushScreen(ChangeBindingPopup.ASSET_URI, ChangeBindingPopup.class);
            popup.setBindingData(uri, bind, index);
        });
        return inputBind;
    }

    @Override
    public void onClosed() {
        super.onClosed();
        bindsManager.registerBinds();

        // TODO: Find a better place to do this in.
        BindsConfig bindsConf = bindsManager.getBindsConfig();
        if (bindsConf != null) {
            bindsConf.getBinds(new SimpleUri("engine:tabbingUI")).stream().findFirst().ifPresent(input -> {
                TabbingManager.tabForwardInput = input;
            });
            bindsConf.getBinds(new SimpleUri("engine:tabbingModifier")).stream().findFirst().ifPresent(input -> {
                TabbingManager.tabBackInputModifier = input;
            });
            bindsConf.getBinds(new SimpleUri("engine:activate")).stream().findFirst().ifPresent(input -> {
                TabbingManager.activateInput = input;
            });
        }
    }

    private final class BindingText extends ReadOnlyBinding<String> {

        private List<Input> binds;
        private int index;

        BindingText(List<Input> binds, int index) {
            this.binds = binds;
            this.index = index;
        }

        @Override
        public String get() {
            if (binds.size() > index) {
                Input input = binds.get(index);
                if (input != null) {
                    return input.getDisplayName();
                }
            }
            return "<" + translationSystem.translate("${engine:menu#not-bound}") + ">";
        }
    }

    private static final class ExtensionBind implements Comparable<ExtensionBind> {
        private SimpleUri uri;
        private RegisterBindButton bind;

        private ExtensionBind(SimpleUri uri, RegisterBindButton bind) {
            this.uri = uri;
            this.bind = bind;
        }

        @Override
        public int compareTo(ExtensionBind o) {
            int descriptionOrder = bind.description().compareTo(o.bind.description());
            if (descriptionOrder == 0) {
                return uri.compareTo(o.uri);
            }
            return descriptionOrder;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (obj instanceof ExtensionBind) {
                ExtensionBind other = (ExtensionBind) obj;
                return Objects.equals(bind.description(), other.bind.description()) && Objects.equals(uri, other.uri);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(uri, bind.description());
        }
    }

}
