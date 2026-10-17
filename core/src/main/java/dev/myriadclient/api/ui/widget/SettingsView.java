package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.setting.Settings;

/** Builds collapsible group sections with a row per setting. */
public final class SettingsView {
	private SettingsView() {
	}

	public static VBox build(Settings settings) {
		VBox box = new VBox(4, 0);
		for (SettingGroup group : settings.groups()) {
			if (group.settings().isEmpty()) continue;
			VBox body = new VBox(3, 0);
			for (Setting<?> s : group.settings()) {
				Widget row = Myriad.ui().settingWidgets().create(s);
				if (row != null) body.add(row);
			}
			Collapsible section = new Collapsible(group::name, body, group::isExpanded, group::setExpanded).indent(10)
				.hint(() -> group.isExpanded() ? "" : String.valueOf(group.settings().stream().filter(Setting::isVisible).count()));
			section.visible(() -> group.settings().stream().anyMatch(Setting::isVisible));
			box.add(section);
		}
		return box;
	}
}
