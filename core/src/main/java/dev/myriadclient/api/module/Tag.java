package dev.myriadclient.api.module;

import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.registry.MyriadId;
import dev.myriadclient.api.setting.SettingColor;

/**
 * What a module is about: Combat, Movement, Render... Myriad core registers the shared set in {@link Tags}, so
 * {@code #combat} in the launcher finds every addon's combat modules, and they share an icon and a colour. Tags don't
 * decide where a module shows in the menu; its {@link ModuleGroup} does. Addons may register more with
 * {@code AddonContext#registerTag}.
 *
 * @param id   {@code <addon id>:<name in snake_case>}; {@code myriad:} for the shared ones
 * @param name the display name ("Combat"); {@code #name} in lower case finds its modules in the launcher
 * @param icon a short glyph; Nerd Font codepoints (see {@link dev.myriadclient.api.ui.Icons}) render from the bundled
 *             icon font
 * @param tint the tag's colour, usually a theme role so it follows the theme
 */
public record Tag(MyriadId id, String name, String icon, SettingColor tint) implements Identified {
	/** The tag's colour right now (ARGB). */
	public int color() {
		return tint.argb();
	}

	/** The display name. */
	@Override
	public String toString() {
		return name;
	}
}
