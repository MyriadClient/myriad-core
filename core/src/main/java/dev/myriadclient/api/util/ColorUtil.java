package dev.myriadclient.api.util;

import java.awt.Color;

/**
 * Helpers for packed ARGB ints ({@code 0xAARRGGBB}), the colour format used throughout Myriad. Channels are 0..255;
 * an alpha of 0 is fully transparent, so a plain {@code 0xRRGGBB} literal draws nothing until given an alpha.
 */
public final class ColorUtil {
	private ColorUtil() {
	}

	/** Packs four channels (0..255; only the low 8 bits of each are kept) into an ARGB int. */
	public static int argb(int a, int r, int g, int b) {
		return (a & 255) << 24 | (r & 255) << 16 | (g & 255) << 8 | (b & 255);
	}

	/** An opaque colour from three channels (0..255). */
	public static int rgb(int r, int g, int b) {
		return argb(255, r, g, b);
	}

	/** The alpha channel, 0..255. */
	public static int alpha(int c) {
		return c >>> 24;
	}

	/** The red channel, 0..255. */
	public static int red(int c) {
		return c >> 16 & 255;
	}

	/** The green channel, 0..255. */
	public static int green(int c) {
		return c >> 8 & 255;
	}

	/** The blue channel, 0..255. */
	public static int blue(int c) {
		return c & 255;
	}

	/** {@code c} with its alpha replaced by {@code alpha}, clamped to 0..255. */
	public static int withAlpha(int c, int alpha) {
		return (Math.clamp(alpha, 0, 255)) << 24 | (c & 0xFFFFFF);
	}

	/** Multiplies the colour's alpha by {@code f} (0..1). */
	public static int fade(int c, float f) {
		return withAlpha(c, Math.round(alpha(c) * Math.clamp(f, 0f, 1f)));
	}

	/** Blends every channel, alpha included, from {@code a} (t = 0) to {@code b} (t = 1); {@code t} is clamped to 0..1. */
	public static int lerp(int a, int b, float t) {
		t = Math.clamp(t, 0f, 1f);
		return argb(
			Math.round(alpha(a) + (alpha(b) - alpha(a)) * t),
			Math.round(red(a) + (red(b) - red(a)) * t),
			Math.round(green(a) + (green(b) - green(a)) * t),
			Math.round(blue(a) + (blue(b) - blue(a)) * t));
	}

	/** Scales RGB by {@code f}, keeping alpha: f &lt; 1 darkens, f &gt; 1 brightens (each channel capped at 255). */
	public static int shade(int c, float f) {
		return argb(alpha(c), Math.min(255, Math.round(red(c) * f)), Math.min(255, Math.round(green(c) * f)), Math.min(255, Math.round(blue(c) * f)));
	}

	/**
	 * A colour from hue, saturation and brightness, each 0..1 (the hue wraps, so 1.2 is 0.2), with {@code alpha}
	 * 0..255.
	 */
	public static int hsb(float h, float s, float b, int alpha) {
		return withAlpha(Color.HSBtoRGB(h, s, b), alpha);
	}

	/** {@code [hue, saturation, brightness]}, each 0..1, of {@code c}'s RGB; alpha is ignored. */
	public static float[] toHsb(int c) {
		return Color.RGBtoHSB(red(c), green(c), blue(c), null);
	}

	/**
	 * A colour whose hue cycles with the clock, one full turn every {@code 10 / speed} seconds; {@code offset} in 0..1
	 * shifts the phase (e.g. per list row). Saturation and brightness are 0..1, alpha 0..255.
	 */
	public static int rainbow(float speed, float offset, float saturation, float brightness, int alpha) {
		float hue = (float) ((System.nanoTime() / 1_000_000_000.0 * speed * 0.1 + offset) % 1.0);
		return hsb(hue, saturation, brightness, alpha);
	}

	/** {@code [red, green, blue, alpha]}, each 0..1, as shaders and GL calls take them (note: alpha last). */
	public static float[] toFloats(int c) {
		return new float[]{red(c) / 255f, green(c) / 255f, blue(c) / 255f, alpha(c) / 255f};
	}

	/** {@code c} as {@code #AARRGGBB} in upper case, alpha always included; {@link #parseHex} reads it back. */
	public static String toHex(int c) {
		return String.format("#%08X", c);
	}

	/**
	 * Parses {@code #RRGGBB} (made opaque) or {@code #AARRGGBB}; the leading '#' and surrounding spaces are optional.
	 * Throws {@link NumberFormatException} for anything that isn't hex.
	 */
	public static int parseHex(String s) {
		s = s.trim();
		if (s.startsWith("#")) s = s.substring(1);
		long v = Long.parseLong(s, 16);
		if (s.length() <= 6) v |= 0xFF000000L;
		return (int) v;
	}
}
