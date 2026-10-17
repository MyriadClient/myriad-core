package dev.myriadclient.api.render;

/**
 * A float that eases towards its target over time. Time-based (not frame-based), so it can be read any number of
 * times per frame.
 */
public final class Animated {
	private float from, to, value;
	private long start;
	private float durationMs;
	private Bezier curve;

	public Animated(float initial) {
		this.from = this.to = this.value = initial;
		this.curve = Bezier.EASE_OUT_QUINT;
	}

	/** Animates to {@code target} over {@code durationMs}. A zero duration snaps. */
	public void animateTo(float target, float durationMs, Bezier curve) {
		if (target == to && isAnimating()) return;
		if (target == to && value == to) return;
		this.from = get();
		this.to = target;
		this.start = System.nanoTime();
		this.durationMs = durationMs;
		this.curve = curve;
		if (durationMs <= 0) snap(target);
	}

	public void snap(float v) {
		from = to = value = v;
		durationMs = 0;
	}

	public float get() {
		if (durationMs <= 0) return value = to;
		float t = (System.nanoTime() - start) / 1_000_000f / durationMs;
		if (t >= 1) {
			durationMs = 0;
			return value = to;
		}
		return value = from + (to - from) * curve.apply(t);
	}

	public float target() {
		return to;
	}

	public boolean isAnimating() {
		return durationMs > 0 && (System.nanoTime() - start) / 1_000_000f < durationMs;
	}
}
