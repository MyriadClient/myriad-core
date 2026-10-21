package dev.myriadclient.api.module;

import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.util.MyriadId;

/**
 * Some of one addon's modules, together in one window of the menu, as the addon's author thinks best: one group for a
 * small addon, several for clear separation in a big one ("Travel", "Building &amp; Mining"). Declare them with
 * {@code AddonContext#registerGroup} and register modules into them with {@code registerModule(group, module)}.
 * Modules registered without a group are in the addon's own window, named after it ({@link #isAddonWindow()}).
 * Where windows go is up to the player: each group is a window of its own, placed and merged like any other.
 *
 * @param id   {@code <addon id>:<name in snake_case>}; the namespace is the addon the group belongs to
 * @param icon a short glyph for the window's title (see {@link dev.myriadclient.api.ui.Icons})
 */
public record ModuleGroup(MyriadId id, String name, String icon) implements Identified {
	/** The path of an addon's own window: the group of the modules it registers without one. */
	public static final String ADDON_WINDOW = "addon";
	/** The icon of an addon's own window. */
	public static final String ADDON_ICON = "";

	/** The group of {@code addonId}'s modules registered without one, shown in a window named {@code addonName}. */
	public static ModuleGroup addonWindow(String addonId, String addonName) {
		return new ModuleGroup(MyriadId.of(addonId, ADDON_WINDOW), addonName, ADDON_ICON);
	}

	/** The mod id of the addon this group belongs to. */
	public String addon() {
		return id.namespace();
	}

	/** Whether this is an addon's own window, for the modules it didn't put in a group. */
	public boolean isAddonWindow() {
		return ADDON_WINDOW.equals(id.path());
	}

	@Override
	public String toString() {
		return name;
	}
}
