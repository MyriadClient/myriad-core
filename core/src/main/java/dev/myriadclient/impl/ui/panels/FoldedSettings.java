package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.setting.Settings;
import dev.myriadclient.api.ui.widget.Collapsible;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.ui.widget.Widget;

import java.util.Map;

/**
 * A module's settings as {@code SettingsView} lays them out, with which groups are open kept in a map of the window's
 * own: a module showing in two windows (its category's, a control panel's) folds them in each on its own. A group not
 * in the map starts as the group is.
 */
final class FoldedSettings {
	private FoldedSettings() {
	}

	static VBox build(Settings settings, Map<SettingGroup, Boolean> open) {
		VBox box = new VBox(4, 0);
		for (SettingGroup group : settings.groups()) {
			if (group.settings().isEmpty()) continue;
			VBox body = new VBox(3, 0);
			for (Setting<?> s : group.settings()) {
				Widget row = Myriad.ui().settingWidgets().create(s);
				if (row != null) body.add(row);
			}
			Collapsible section = new Collapsible(group::name, body, () -> isOpen(open, group), v -> open.put(group, v)).indent(10)
				.hint(() -> isOpen(open, group) ? "" : String.valueOf(group.settings().stream().filter(Setting::isVisible).count()));
			section.visible(() -> group.settings().stream().anyMatch(Setting::isVisible));
			box.add(section);
		}
		return box;
	}

	private static boolean isOpen(Map<SettingGroup, Boolean> open, SettingGroup group) {
		return open.getOrDefault(group, group.isExpanded());
	}
}
