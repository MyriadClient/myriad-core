package dev.myriadclient.api.setting;

import dev.myriadclient.api.util.ColorUtil;
import org.jetbrains.annotations.ApiStatus;

import java.util.function.ToIntFunction;

/**
 * A colour value as edited by {@link ColorSetting}: a fixed ARGB colour, a rainbow, or a <em>theme role</em> such as
 * {@link Mode#ACCENT} or {@link Mode#RED} that follows the active theme. Prefer roles for defaults so modules match
 * whatever theme the user picks. Call {@link #argb()} every frame rather than caching the result.
 *
 * <pre>{@code
 * SettingColor fill = SettingColor.role(SettingColor.Mode.ACCENT, 80);   // the theme's accent, about a third opaque
 * int argb = fill.argb();
 * }</pre>
 *
 * @param color the ARGB colour for {@link Mode#STATIC}; for a rainbow or a role only its alpha counts
 * @param mode  fixed, rainbow, or which theme role
 */
public record SettingColor(int color, Mode mode) {
	private static ToIntFunction<Mode> palette = m -> 0xFF7FD8AE;

	/** Where a {@link SettingColor}'s colour comes from. */
	public enum Mode {
		/** The stored ARGB colour as it is. */
		STATIC,
		/** A rainbow cycling over time, with the stored colour's alpha. */
		RAINBOW,
		/** The theme's accent colour. The constants from here on are theme roles: the active theme's colours. */
		ACCENT,
		/** The theme's second accent. */
		SECONDARY,
		/** The theme's red. */
		RED,
		/** The theme's green. */
		GREEN,
		/** The theme's yellow. */
		YELLOW,
		/** The theme's blue. */
		BLUE,
		/** The theme's magenta. */
		MAGENTA,
		/** The theme's cyan. */
		CYAN,
		/** The theme's text colour. */
		TEXT;

		/** Whether this is a theme role (not {@link #STATIC} or {@link #RAINBOW}). */
		public boolean isThemeRole() {
			return ordinal() >= ACCENT.ordinal();
		}
	}

	/** A fixed colour, {@code 0xAARRGGBB}. */
	public static SettingColor of(int argb) {
		return new SettingColor(argb, Mode.STATIC);
	}

	/** A theme role at full opacity. */
	public static SettingColor role(Mode role) {
		return new SettingColor(0xFFFFFFFF, role);
	}

	/** A theme role with the given alpha (0-255). */
	public static SettingColor role(Mode role, int alpha) {
		return new SettingColor(ColorUtil.withAlpha(0xFFFFFF, alpha), role);
	}

	/** The theme's accent at full opacity. */
	public static SettingColor accent() {
		return role(Mode.ACCENT);
	}

	/** The colour to draw with right now. Rainbow and theme roles keep the stored colour's alpha. */
	public int argb() {
		return switch (mode) {
			case STATIC -> color;
			case RAINBOW -> ColorUtil.rainbow(1f, 0f, 0.7f, 1f, ColorUtil.alpha(color));
			default -> ColorUtil.withAlpha(palette.applyAsInt(mode), ColorUtil.alpha(color));
		};
	}

	/** This colour with {@code argb} as its stored colour (for a role or rainbow, its alpha); same mode. */
	public SettingColor withColor(int argb) {
		return new SettingColor(argb, mode);
	}

	/** This colour in {@code mode}, keeping the stored colour. */
	public SettingColor withMode(Mode mode) {
		return new SettingColor(color, mode);
	}

	/** Core's: where theme-role colours get the current theme's palette. */
	@ApiStatus.Internal
	public static void setPaletteProvider(ToIntFunction<Mode> provider) {
		palette = provider;
	}

	/**
	 * A number that changes whenever a theme role resolves to a different colour (the user switched or edited the
	 * theme). Compare it with the value from when you cached colours to know when to rebuild.
	 */
	public static int paletteStamp() {
		int h = 1;
		for (Mode m : Mode.values()) {
			if (m != Mode.STATIC && m != Mode.RAINBOW) h = h * 31 + palette.applyAsInt(m);
		}
		return h;
	}
}
