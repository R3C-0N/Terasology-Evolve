// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu.settings;

import org.terasology.engine.i18n.TranslationSystem;
import org.terasology.nui.UIWidget;
import org.terasology.nui.databinding.Binding;
import org.terasology.nui.databinding.ReadOnlyBinding;
import org.terasology.nui.layouts.ColumnLayout;
import org.terasology.nui.layouts.RowLayout;
import org.terasology.nui.layouts.RowLayoutHint;
import org.terasology.nui.widgets.UIBox;
import org.terasology.nui.widgets.UIButton;
import org.terasology.nui.widgets.UICheckbox;
import org.terasology.nui.widgets.UILabel;
import org.terasology.nui.widgets.UISlider;
import org.terasology.nui.widgets.UISpace;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Builds what every options tab is made of, in the HeroCraft design: framed sections, rows of name, control and frame-time
 * impact, and the controls themselves.
 * <p>
 * Rows are built in code rather than in the {@code .ui} files so that a setting is declared once, its control and its cost
 * side by side, instead of being spread over a layout, a binding and a label.
 */
public class SettingsRows {
    /** How the on/off label agrees with the setting's name: masculine singular, the default. */
    public static final String MASCULINE = "";
    public static final String FEMININE = "-f";
    public static final String MASCULINE_PLURAL = "-mp";
    public static final String FEMININE_PLURAL = "-fp";

    private static final float NAME_WIDTH = 0.32f;
    private static final float CONTROL_WIDTH = 0.40f;
    private static final float IMPACT_WIDTH = 0.28f;
    private static final float NAME_WIDTH_WITHOUT_IMPACT = 0.45f;
    private static final int ROW_SPACING = 16;

    private final TranslationSystem translationSystem;

    public SettingsRows(TranslationSystem translationSystem) {
        this.translationSystem = translationSystem;
    }

    /** Adds a widget to a line at its own full size, skin margins included. */
    public static void fit(RowLayout line, UIWidget widget) {
        line.addWidget(new SizedToContent(widget, false), new RowLayoutHint().setUseContentWidth(true));
    }

    /** Adds a widget to a line, sharing the width the fitted widgets leave. */
    public static void fill(RowLayout line, UIWidget widget) {
        line.addWidget(new SizedToContent(widget, true), new RowLayoutHint());
    }

    public static Binding<Boolean> bindBoolean(BooleanSupplier get, Consumer<Boolean> set) {
        return new Binding<Boolean>() {
            @Override
            public Boolean get() {
                return get.getAsBoolean();
            }

            @Override
            public void set(Boolean value) {
                set.accept(value);
            }
        };
    }

    public static Binding<Float> bindFloat(Supplier<Float> get, Consumer<Float> set) {
        return new Binding<Float>() {
            @Override
            public Float get() {
                return get.get();
            }

            @Override
            public void set(Float value) {
                set.accept(value);
            }
        };
    }

    public String translate(String text) {
        return translationSystem.translate(text);
    }

    /** Translates a pattern such as {@code "%d chunks"}, then fills it in. */
    public String format(String pattern, Object... arguments) {
        return String.format(Locale.ROOT, translate(pattern), arguments);
    }

    public String percent(float value) {
        return format("${engine:menu#opt-percent}", Math.round(value));
    }

    public UILabel label(String text, String family) {
        UILabel label = new UILabel(translate(text));
        label.setFamily(family);
        return label;
    }

    /** A label whose text is asked for on every frame. */
    public UILabel label(Supplier<String> text, String family) {
        UILabel label = new UILabel();
        label.bindText(new ReadOnlyBinding<String>() {
            @Override
            public String get() {
                return text.get();
            }
        });
        label.setFamily(family);
        return label;
    }

    public ColumnLayout column(int spacing) {
        ColumnLayout column = new ColumnLayout();
        column.setColumns(1);
        column.setVerticalSpacing(spacing);
        column.setFillVerticalSpace(false);
        return column;
    }

    /** A horizontal line of widgets that styles none of them. */
    public RowLayout line(int spacing) {
        RowLayout line = new RowLayout();
        line.setFamily("choice-row");
        line.setHorizontalSpacing(spacing);
        return line;
    }

    public UIBox box(String family, UIWidget content) {
        UIBox box = new UIBox();
        box.setFamily(family);
        box.setContent(content);
        return box;
    }

    /** Centres a widget at its own width, which a column alone would stretch to the full width. */
    public RowLayout centered(UIWidget widget) {
        RowLayout line = line(0);
        line.addWidget(new UISpace(), new RowLayoutHint());
        fit(line, widget);
        line.addWidget(new UISpace(), new RowLayoutHint());
        return line;
    }

    /**
     * A framed section: a plaque title, an optional note, then its rows with a hairline above each.
     *
     * @param note the note under the title, or null for none
     */
    public UIBox section(String title, String note, List<? extends UIWidget> rows) {
        ColumnLayout body = column(6);
        body.addWidget(centered(label(title, "panel-title")));
        if (note != null) {
            body.addWidget(label(note, "section-note"));
        }
        for (UIWidget row : rows) {
            body.addWidget(ColorBlock.of(1, 1, SettingsPalette.HAIRLINE));
            body.addWidget(row);
        }
        return box("panel-slim", body);
    }

    /**
     * A setting without a frame-time cost: its name and help on the left, the control on the right.
     *
     * @param help the line under the name, or null for none
     */
    public RowLayout row(String name, String help, UIWidget control) {
        RowLayout row = settingRow();
        row.addWidget(nameAndHelp(name, help), new RowLayoutHint(NAME_WIDTH_WITHOUT_IMPACT));
        row.addWidget(new SizedToContent(control, true), new RowLayoutHint(1 - NAME_WIDTH_WITHOUT_IMPACT));
        return row;
    }

    /**
     * A setting with its frame-time cost: name and help, control, then the impact pips.
     *
     * @param help the line under the name, or null for none
     * @param measured whether the cost was measured, which the impact column marks with an accent edge
     * @param gain what the setting is worth, such as {@code "-4.2 ms"} or {@code "estimated"}
     */
    public RowLayout row(String name, String help, UIWidget control, Impact impact, boolean measured, String gain) {
        RowLayout row = settingRow();
        row.addWidget(nameAndHelp(name, help), new RowLayoutHint(NAME_WIDTH));
        row.addWidget(new SizedToContent(control, true), new RowLayoutHint(CONTROL_WIDTH));
        row.addWidget(impactBadge(impact, measured, gain), new RowLayoutHint(IMPACT_WIDTH));
        return row;
    }

    public UIButton button(String text, String family, Runnable action) {
        UIButton button = new UIButton();
        button.setText(translate(text));
        button.setFamily(family);
        button.subscribe(widget -> action.run());
        return button;
    }

    /** One button per option; the current one is lit, and it follows the setting whatever changes it. */
    public <T> RowLayout choice(List<T> options, Function<T, String> name, Supplier<T> current, Consumer<T> choose) {
        RowLayout line = line(6);
        for (T option : options) {
            UIButton button = button(name.apply(option), "choice-off", () -> choose.accept(option));
            button.bindFamily(new ReadOnlyBinding<String>() {
                @Override
                public String get() {
                    return Objects.equals(current.get(), option) ? "choice-on" : "choice-off";
                }
            });
            fit(line, button);
        }
        line.addWidget(new UISpace(), new RowLayoutHint());
        return line;
    }

    /**
     * A checkbox followed by the state it stands for.
     *
     * @param agreement one of {@link #MASCULINE}, {@link #FEMININE}, {@link #MASCULINE_PLURAL}, {@link #FEMININE_PLURAL}
     */
    public RowLayout toggle(String agreement, Binding<Boolean> checked) {
        UICheckbox checkbox = new UICheckbox();
        checkbox.bindChecked(checked);
        String on = translate("${engine:menu#opt-on" + agreement + "}");
        String off = translate("${engine:menu#opt-off" + agreement + "}");
        RowLayout line = line(12);
        fit(line, checkbox);
        fill(line, label(() -> Boolean.TRUE.equals(checked.get()) ? on : off, "setting-state"));
        return line;
    }

    /** A slider in whole steps, whose ticker shows {@code readout} of its value. */
    public UISlider slider(float minimum, float maximum, float step, Binding<Float> value, Function<Float, String> readout) {
        UISlider slider = new UISlider();
        slider.setMinimum(minimum);
        slider.setRange(maximum - minimum);
        slider.setIncrement(step);
        slider.setPrecision(0);
        slider.setLabelFunction(readout);
        slider.bindValue(value);
        return slider;
    }

    private RowLayout settingRow() {
        RowLayout row = new RowLayout();
        row.setFamily("setting-row");
        row.setHorizontalSpacing(ROW_SPACING);
        return row;
    }

    private ColumnLayout nameAndHelp(String name, String help) {
        ColumnLayout text = column(2);
        text.addWidget(label(name, "setting-label"));
        if (help != null) {
            text.addWidget(label(help, "setting-help"));
        }
        return text;
    }

    private RowLayout impactBadge(Impact impact, boolean measured, String gain) {
        ColumnLayout texts = column(0);
        texts.addWidget(label(impact.getLabel(), impact.getFamily()));
        texts.addWidget(label(gain, "setting-gain"));
        RowLayout badge = line(10);
        fit(badge, ColorBlock.of(3, 1, measured ? SettingsPalette.ACCENT : SettingsPalette.HAIRLINE));
        badge.addWidget(texts, new RowLayoutHint());
        fit(badge, new SegmentGauge(3, 10, 24, 2, impact::pip));
        return badge;
    }
}
