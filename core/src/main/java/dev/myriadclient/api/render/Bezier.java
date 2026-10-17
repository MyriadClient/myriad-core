package dev.myriadclient.api.render;

/**
 * A CSS/Hyprland-style cubic bezier easing curve through (0,0), (x1,y1), (x2,y2), (1,1).
 */
public record Bezier(String name, float x1, float y1, float x2, float y2) {
	public static final Bezier LINEAR = new Bezier("linear", 0, 0, 1, 1);
	public static final Bezier EASE_OUT_QUINT = new Bezier("easeOutQuint", 0.23f, 1f, 0.32f, 1f);
	public static final Bezier EASE_IN_OUT_CUBIC = new Bezier("easeInOutCubic", 0.65f, 0.05f, 0.36f, 1f);
	public static final Bezier EASE_OUT_EXPO = new Bezier("easeOutExpo", 0.16f, 1f, 0.3f, 1f);
	public static final Bezier OVERSHOT = new Bezier("overshot", 0.05f, 0.9f, 0.1f, 1.1f);
	public static final Bezier SMOOTH = new Bezier("smooth", 0.25f, 0.1f, 0.25f, 1f);

	public static final Bezier[] PRESETS = {EASE_OUT_QUINT, EASE_OUT_EXPO, EASE_IN_OUT_CUBIC, OVERSHOT, SMOOTH, LINEAR};

	/** Maps linear progress {@code t} (0..1) to eased progress. */
	public float apply(float t) {
		if (t <= 0) return 0;
		if (t >= 1) return 1;
		// Solve x(s) = t with Newton-Raphson, falling back to bisection.
		float s = t;
		for (int i = 0; i < 8; i++) {
			float x = sample(s, x1, x2) - t;
			if (Math.abs(x) < 1e-4f) return sample(s, y1, y2);
			float d = derivative(s, x1, x2);
			if (Math.abs(d) < 1e-6f) break;
			s -= x / d;
		}
		float lo = 0, hi = 1;
		s = t;
		for (int i = 0; i < 20; i++) {
			float x = sample(s, x1, x2);
			if (Math.abs(x - t) < 1e-4f) break;
			if (x < t) lo = s;
			else hi = s;
			s = (lo + hi) / 2;
		}
		return sample(s, y1, y2);
	}

	private static float sample(float s, float p1, float p2) {
		float inv = 1 - s;
		return 3 * inv * inv * s * p1 + 3 * inv * s * s * p2 + s * s * s;
	}

	private static float derivative(float s, float p1, float p2) {
		float inv = 1 - s;
		return 3 * inv * inv * p1 + 6 * inv * s * (p2 - p1) + 3 * s * s * (1 - p2);
	}

	public static Bezier byName(String name) {
		for (Bezier b : PRESETS) if (b.name.equalsIgnoreCase(name)) return b;
		return EASE_OUT_QUINT;
	}
}
