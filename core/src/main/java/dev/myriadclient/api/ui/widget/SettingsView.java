package dev.myriadclient.api.ui.widget;

import java.util.List;

import java.util.ArrayList;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.setting.Settings;
import org.jetbrains.annotations.ApiStatus;

import java.util.function.Predicate;

/**
 * Builds settings editors from {@link Settings}: a row per setting, made by
 * {@link dev.myriadclient.api.ui.Ui#settingWidgets()} (settings of a class with no registered editor are left out).
 * Rows read and write the settings live, and hide themselves while their setting isn't visible.
 *
 * <pre>{@code
 * @Override
 * protected void build(VBox content) {
 *     content.add(SettingsView.build(settings));
 * }
 * }</pre>
 */
public final class SettingsView {
	private SettingsView() {
	}

	/**
	 * Every setting of {@code settings} that {@code shown} lets through, as plain rows with no group sections: for
	 * something with a few settings of its own, already under a heading.
	 */
	@ApiStatus.AvailableSince("1.4")
	public static VBox rows(Settings settings, Predicate<Setting<?>> shown) {
		VBox box = new VBox(3, 0);
		for (Setting<?> s : settings.all()) {
			if (!shown.test(s)) continue;
			Widget row = Myriad.ui().settingWidgets().create(s);
			if (row != null) box.add(row);
		}
		return box;
	}

	/**
	 * Every group of {@code settings} as a foldable section (named after the group, folded as the group remembers, its
	 * count of visible settings shown while folded) holding a row per setting. Empty groups are left out, and a section
	 * hides while none of its settings is visible.
	 */
	public static VBox build(Settings settings) {
		return buildGroups(settings);
	}

	/**
	 * The groups of {@code settings} in the order they're shown: as made, but a module's own "Module" group (its keybind,
	 * hold, list and chat settings) always last, so it's in the same place for every module whatever groups it adds.
	 */
	@ApiStatus.AvailableSince("2.6")
	public static List<SettingGroup> displayOrder(Settings settings) {
		List<SettingGroup> ordered = new ArrayList<>(settings.groups());
		for (SettingGroup g : settings.groups()) if (g.name().equals("Module")) {
			ordered.remove(g);
			ordered.add(g);
		}
		return ordered;
	}

	private static VBox buildGroups(Settings settings) {
		VBox box = new VBox(4, 0);
		for (SettingGroup group : SettingsView.displayOrder(settings)) {
			if (group.settings().isEmpty()) continue;
			VBox body = new VBox(3, 0);
			for (Setting<?> s : group.settings()) {
				Widget row = Myriad.ui().settingWidgets().create(s);
				if (row != null) body.add(row);
			}
			Collapsible section = new Collapsible(group::name, body, group::isExpanded, group::setExpanded).indent(10)
				.hint(() -> group.isExpanded() ? "" : String.valueOf(group.settings().stream().filter(Setting::isVisible).count()));
			section.visible(() -> group.isVisible() && group.settings().stream().anyMatch(Setting::isVisible));
			box.add(section);
		}
		return box;
	}
}
