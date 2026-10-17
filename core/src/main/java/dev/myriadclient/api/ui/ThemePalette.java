package dev.myriadclient.api.ui;

import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.util.ColorUtil;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * A terminal-style colour scheme (background, foreground, selection, accent and the 16 ANSI colours) and the mapping
 * from it to every colour a Myriad theme has. Build themes from editor or terminal schemes without picking 20 colours
 * by hand. Border and rounding values are optional.
 */
public record ThemePalette(int background, int foreground, int selection, int accent, int[] ansi,
						   @Nullable Integer borderFrom, @Nullable Integer borderTo, @Nullable Integer inactiveBorder, @Nullable Integer rounding) {
	public int ansi(int i) {
		return ansi[i];
	}

	/** A palette from the colours a theme preset usually names; the other ANSI slots use the foreground. */
	public static ThemePalette of(int background, int foreground, int selection, int accent, int secondary,
								  int red, int green, int yellow, int blue, int magenta, int cyan) {
		int[] ansi = new int[16];
		Arrays.fill(ansi, foreground);
		ansi[1] = red;
		ansi[2] = green;
		ansi[3] = yellow;
		ansi[4] = blue;
		ansi[5] = magenta;
		ansi[6] = cyan;
		return new ThemePalette(background, foreground, selection, accent, ansi, null, secondary, null, null);
	}

	/** Writes the palette into a theme. Only colours (and rounding, if set) change. */
	public void apply(ThemeSettings t) {
		int bg = background, fg = foreground, sel = selection;
		t.accent.set(SettingColor.of(accent));
		int secondary = borderTo != null && (borderTo & 0xFFFFFF) != (accent & 0xFFFFFF) ? borderTo | 0xFF000000 : ansi(5);
		t.secondary.set(SettingColor.of(secondary));
		t.activeBorderFrom.set(borderFrom != null ? SettingColor.of(borderFrom) : SettingColor.role(SettingColor.Mode.ACCENT));
		t.activeBorderTo.set(borderTo != null ? SettingColor.of(borderTo) : SettingColor.role(SettingColor.Mode.SECONDARY));
		t.inactiveBorder.set(SettingColor.of(inactiveBorder != null ? inactiveBorder : ColorUtil.withAlpha(sel, 0xA0)));
		t.text.set(SettingColor.of(fg));
		t.textDim.set(SettingColor.of(ColorUtil.lerp(fg, bg, 0.4f)));
		t.windowBackground.set(SettingColor.of(ColorUtil.withAlpha(bg, 0xE0)));
		t.titleBackground.set(SettingColor.of(ColorUtil.withAlpha(ColorUtil.lerp(bg, 0xFF000000, 0.3f), 0x70)));
		t.surface.set(SettingColor.of(ColorUtil.withAlpha(ColorUtil.lerp(bg, sel, 0.7f), 0x90)));
		t.surfaceHover.set(SettingColor.of(ColorUtil.withAlpha(ColorUtil.lerp(sel, fg, 0.12f), 0xC0)));
		t.desktopTint.set(SettingColor.of(ColorUtil.withAlpha(bg, 0x80)));
		t.barBackground.set(SettingColor.of(ColorUtil.withAlpha(bg, 0xEA)));
		t.red.set(SettingColor.of(ansi(1)));
		t.green.set(SettingColor.of(ansi(2)));
		t.yellow.set(SettingColor.of(ansi(3)));
		t.blue.set(SettingColor.of(ansi(4)));
		t.magenta.set(SettingColor.of(ansi(5)));
		t.cyan.set(SettingColor.of(ansi(6)));
		// Hyprland-style rounding is in pixels; UI units are roughly two pixels.
		if (rounding != null) t.rounding.set(Math.round(rounding / 2f));
	}
}
