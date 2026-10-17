package dev.myriadclient.api.addon;

import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.setting.Settings;
import dev.myriadclient.api.util.MyriadId;

/**
 * Settings that belong to an addon rather than to one of its modules: display units, a chat prefix, what a window
 * shows. Declared with {@link AddonContext#settings(String)}, shown under the addon in the Addons panel, and saved with
 * the profile like module settings (with the same migration rules: only non-defaults are written, unknown keys kept).
 *
 * <pre>{@code
 * AddonSettings prefs = ctx.settings("Waypoints");
 * BoolSetting metric = prefs.group("General").bool("Metric Distances").defaultValue(true).build();
 * }</pre>
 */
public final class AddonSettings implements Identified {
	private final MyriadId id;
	private final String name;
	private final Settings settings = new Settings();

	public AddonSettings(MyriadId id, String name) {
		this.id = id;
		this.name = name;
	}

	@Override
	public MyriadId id() {
		return id;
	}

	public String name() {
		return name;
	}

	public Settings settings() {
		return settings;
	}

	/** Shortcut for {@code settings().group(name)}. */
	public dev.myriadclient.api.setting.SettingGroup group(String name) {
		return settings.group(name);
	}
}
