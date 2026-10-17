package dev.myriadclient.impl.ui;

import dev.myriadclient.api.addon.AddonContext;
import dev.myriadclient.api.ui.Theme;
import dev.myriadclient.api.ui.ThemePalette;


/** Myriad's built-in theme presets. Addons can register more (see Theme and ThemePalette). */
public final class CoreThemes {
	private CoreThemes() {
	}

	public static void register(AddonContext ctx) {
		// Myriad's own look, the logo's colours: the plain ThemeSettings defaults.
		ctx.registerTheme(new Theme(ThemeManager.DEFAULT, "Myriad", t -> {
		}));
		preset(ctx, "tokyo_night", "Tokyo Night", ThemePalette.of(0xFF1A1B26, 0xFFC0CAF5, 0xFF33467C, 0xFF7AA2F7, 0xFFBB9AF7,
			0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68, 0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF));
		preset(ctx, "gruvbox", "Gruvbox", ThemePalette.of(0xFF282828, 0xFFEBDBB2, 0xFF504945, 0xFFFABD2F, 0xFFFE8019,
			0xFFFB4934, 0xFFB8BB26, 0xFFFABD2F, 0xFF83A598, 0xFFD3869B, 0xFF8EC07C));
		// Myriad's default look before 0.3.1.
		preset(ctx, "catppuccin_mocha", "Catppuccin Mocha", ThemePalette.of(0xFF1E1E2E, 0xFFCDD6F4, 0xFF45475A, 0xFF89B4FA, 0xFFCBA6F7,
			0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF, 0xFF89B4FA, 0xFFCBA6F7, 0xFF94E2D5));
	}

	private static void preset(AddonContext ctx, String path, String name, ThemePalette palette) {
		ctx.registerTheme(new Theme(ctx.id(path), name, palette::apply));
	}
}
