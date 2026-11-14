package dev.myriadclient.api.addon;

import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.registry.MyriadId;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.setting.Settings;

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

	/**
	 * An empty set of settings with {@code id} and display {@code name}. Addons get theirs from
	 * {@link AddonContext#settings(String)}, which makes one and registers it; one made here and never registered is
	 * neither shown nor saved.
	 */
	public AddonSettings(MyriadId id, String name) {
		this.id = id;
		this.name = name;
	}

	/** {@code <mod id>:<name in snake_case>}: the key the profile saves these settings under. */
	@Override
	public MyriadId id() {
		return id;
	}

	/** The name given to {@link AddonContext#settings(String)}, shown as a heading in the Addons panel. */
	public String name() {
		return name;
	}

	/** The settings themselves, in groups. */
	public Settings settings() {
		return settings;
	}

	/** Shortcut for {@code settings().group(name)}: the group called {@code name}, made if it doesn't exist yet. */
	public SettingGroup group(String name) {
		return settings.group(name);
	}
}
