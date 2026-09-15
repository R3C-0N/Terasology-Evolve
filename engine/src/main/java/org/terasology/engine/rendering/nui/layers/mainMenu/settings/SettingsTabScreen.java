// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.nui.layers.mainMenu.settings;

import org.terasology.engine.config.Config;
import org.terasology.engine.i18n.TranslationSystem;
import org.terasology.engine.registry.In;
import org.terasology.engine.rendering.nui.CoreScreenLayer;
import org.terasology.engine.rendering.nui.NUIManager;
import org.terasology.engine.rendering.nui.animation.MenuAnimationSystems;
import org.terasology.nui.WidgetUtil;
import org.terasology.nui.widgets.UIButton;
import org.terasology.nui.widgets.UILabel;

import java.util.Locale;

/**
 * One tab of the options screen. Every tab carries the same header, its title, a summary and the tab bar, and a back
 * button that leaves the options altogether.
 * <p>
 * Switching tabs replaces this screen with the other one instead of stacking it, so going back from any tab returns to
 * where the options were opened from. The configuration is saved whenever a tab closes.
 */
public abstract class SettingsTabScreen extends CoreScreenLayer {

    @In
    private Config config;

    @In
    private TranslationSystem translationSystem;

    private SettingsRows rows;

    /** The tab this screen is. */
    protected abstract SettingsTab getTab();

    /** Styles the tab bar with this tab lit, wires the other tabs and the back button. Call first from {@code initialise}. */
    protected final void initialiseTabs() {
        setAnimationSystem(MenuAnimationSystems.createDefaultSwipeAnimation());
        for (SettingsTab tab : SettingsTab.values()) {
            UIButton button = find(tab.getButtonId(), UIButton.class);
            if (button == null) {
                continue;
            }
            if (tab == getTab()) {
                button.setFamily("tab-active");
            } else {
                button.setFamily("tab-bar");
                button.subscribe(widget -> switchTo(tab));
            }
        }
        WidgetUtil.trySubscribe(this, "close", button -> triggerBackAnimation());
    }

    protected final SettingsRows rows() {
        if (rows == null) {
            rows = new SettingsRows(translationSystem);
        }
        return rows;
    }

    /** Writes the summary next to the title: the tab's name and how many settings it holds. */
    protected final void setSettingCount(int count) {
        UILabel summary = find("tabSummary", UILabel.class);
        if (summary != null) {
            String text = translationSystem.translate(getTab().getLabel()) + " · "
                    + String.format(Locale.ROOT, translationSystem.translate("${engine:menu#opt-settings-count}"), count);
            summary.setText(text.toUpperCase(Locale.ROOT));
        }
    }

    private void switchTo(SettingsTab tab) {
        NUIManager manager = getManager();
        // Push first: closing a screen that is not on top leaves the screens under it untouched.
        manager.pushScreen(manager.createScreen(tab.getAssetUri()));
        manager.closeScreen(this);
    }

    @Override
    public void onClosed() {
        super.onClosed();
        config.save();
    }

    @Override
    public boolean isLowerLayerVisible() {
        return false;
    }
}
