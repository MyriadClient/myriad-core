package dev.myriadclient.api.module;

import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.util.MyriadId;

/**
 * A module category. Myriad core registers the conventional set in {@link Categories}; addons may register more in
 * {@code MyriadAddon#registerCategories}. Categories without modules are hidden.
 *
 * @param icon a short glyph shown in the UI; Nerd Font codepoints (e.g. {@code "\uf05b"}) render from the bundled icon font
 * @param tint the category's colour, usually a theme role so it follows the theme
 */
public record Category(MyriadId id, String name, String icon, SettingColor tint) implements Identified {
	/** The category colour right now (ARGB). */
	public int color() {
		return tint.argb();
	}

	@Override
	public String toString() {
		return name;
	}
}
