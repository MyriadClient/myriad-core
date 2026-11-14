package dev.myriadclient.api.ui.draw;

/**
 * A float that eases towards its target over time. Time-based (not frame-based), so it can be read any number of
 * times per frame. Keep one per animated value in a field, call {@link #animateTo} with where it should be each frame
 * (a call with the same target changes nothing), and draw with {@link #get()}. Not thread-safe; use it on the render
 * thread.
 *
 * <pre>{@code
 * private final Animated open = new Animated(0);
 * // each frame:
 * open.animateTo(expanded ? 1 : 0, 160, Bezier.EASE_OUT_QUINT);
 * float h = ROW + bodyHeight * open.get();
 * }</pre>
 */
public final class Animated {
	private float from, to, value;
	private long start;
	private float durationMs;
	private Bezier curve;

	/** A value resting at {@code initial}. */
	public Animated(float initial) {
		this.from = this.to = this.value = initial;
		this.curve = Bezier.EASE_OUT_QUINT;
	}

	/**
	 * Animates to {@code target} over {@code durationMs}, from wherever it is now. A zero duration snaps. Asking for
	 * the target it's already heading to (or resting at) does nothing, so the animation isn't restarted every frame.
	 */
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

	/** Jumps to {@code v} and rests there, ending any animation. */
	public void snap(float v) {
		from = to = value = v;
		durationMs = 0;
	}

	/** The value now: eased between where the animation started and its target, or the target once it's done. */
	public float get() {
		if (durationMs <= 0) return value = to;
		float t = (System.nanoTime() - start) / 1_000_000f / durationMs;
		if (t >= 1) {
			durationMs = 0;
			return value = to;
		}
		return value = from + (to - from) * curve.apply(t);
	}

	/** Where it's heading (or resting). */
	public float target() {
		return to;
	}

	/** Whether an animation is still under way. */
	public boolean isAnimating() {
		return durationMs > 0 && (System.nanoTime() - start) / 1_000_000f < durationMs;
	}
}
