package dev.myriadclient.api.util;

/** Helpers for packed ARGB ints, the colour format used throughout Myriad. */
public final class ColorUtil {
	private ColorUtil() {
	}

	public static int argb(int a, int r, int g, int b) {
		return (a & 255) << 24 | (r & 255) << 16 | (g & 255) << 8 | (b & 255);
	}

	public static int rgb(int r, int g, int b) {
		return argb(255, r, g, b);
	}

	public static int alpha(int c) {
		return c >>> 24;
	}

	public static int red(int c) {
		return c >> 16 & 255;
	}

	public static int green(int c) {
		return c >> 8 & 255;
	}

	public static int blue(int c) {
		return c & 255;
	}

	public static int withAlpha(int c, int alpha) {
		return (Math.clamp(alpha, 0, 255)) << 24 | (c & 0xFFFFFF);
	}

	/** Multiplies the colour's alpha by {@code f} (0..1). */
	public static int fade(int c, float f) {
		return withAlpha(c, Math.round(alpha(c) * Math.clamp(f, 0f, 1f)));
	}

	public static int lerp(int a, int b, float t) {
		t = Math.clamp(t, 0f, 1f);
		return argb(
			Math.round(alpha(a) + (alpha(b) - alpha(a)) * t),
			Math.round(red(a) + (red(b) - red(a)) * t),
			Math.round(green(a) + (green(b) - green(a)) * t),
			Math.round(blue(a) + (blue(b) - blue(a)) * t));
	}

	/** Scales RGB by {@code f}, keeping alpha. f &gt; 1 brightens. */
	public static int shade(int c, float f) {
		return argb(alpha(c), Math.min(255, Math.round(red(c) * f)), Math.min(255, Math.round(green(c) * f)), Math.min(255, Math.round(blue(c) * f)));
	}

	/** hue/saturation/brightness in 0..1. */
	public static int hsb(float h, float s, float b, int alpha) {
		return withAlpha(java.awt.Color.HSBtoRGB(h, s, b), alpha);
	}

	public static float[] toHsb(int c) {
		return java.awt.Color.RGBtoHSB(red(c), green(c), blue(c), null);
	}

	/** A hue cycling colour; {@code offset} in 0..1 shifts the phase (e.g. per list row). */
	public static int rainbow(float speed, float offset, float saturation, float brightness, int alpha) {
		float hue = (float) ((System.nanoTime() / 1_000_000_000.0 * speed * 0.1 + offset) % 1.0);
		return hsb(hue, saturation, brightness, alpha);
	}

	public static float[] toFloats(int c) {
		return new float[]{red(c) / 255f, green(c) / 255f, blue(c) / 255f, alpha(c) / 255f};
	}

	public static String toHex(int c) {
		return String.format("#%08X", c);
	}

	/** Parses {@code #RRGGBB} or {@code #AARRGGBB} (leading '#' optional). */
	public static int parseHex(String s) {
		s = s.trim();
		if (s.startsWith("#")) s = s.substring(1);
		long v = Long.parseLong(s, 16);
		if (s.length() <= 6) v |= 0xFF000000L;
		return (int) v;
	}
}
