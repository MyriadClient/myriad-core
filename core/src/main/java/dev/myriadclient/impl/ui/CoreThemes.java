package dev.myriadclient.impl.ui;

import dev.myriadclient.api.addon.AddonContext;
import dev.myriadclient.api.ui.Theme;
import dev.myriadclient.api.ui.ThemePalette;


/** Myriad's built-in theme presets. Addons can register more (see Theme and ThemePalette). */
public final class CoreThemes {
	private CoreThemes() {
	}

	public static void register(AddonContext ctx) {
		// Myriad's own look: the plain ThemeSettings defaults.
		ctx.registerTheme(new Theme(ThemeManager.DEFAULT, "Myriad", t -> {
		}));
		preset(ctx, "tokyo_night", "Tokyo Night", ThemePalette.of(0xFF1A1B26, 0xFFC0CAF5, 0xFF33467C, 0xFF7AA2F7, 0xFFBB9AF7,
			0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68, 0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF));
		preset(ctx, "gruvbox", "Gruvbox", ThemePalette.of(0xFF282828, 0xFFEBDBB2, 0xFF504945, 0xFFFABD2F, 0xFFFE8019,
			0xFFFB4934, 0xFFB8BB26, 0xFFFABD2F, 0xFF83A598, 0xFFD3869B, 0xFF8EC07C));
	}

	private static void preset(AddonContext ctx, String path, String name, ThemePalette palette) {
		ctx.registerTheme(new Theme(ctx.id(path), name, palette::apply));
	}
}
