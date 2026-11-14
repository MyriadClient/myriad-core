package dev.myriadclient.api.util;

/**
 * A stopwatch for delays and cooldowns.
 *
 * <pre>{@code
 * private final Timer placeTimer = new Timer();
 *
 * if (placeTimer.passed(delay.get())) {
 *     place();
 *     placeTimer.reset();
 * }
 * }</pre>
 */
public final class Timer {
	private long start;

	/** A timer that has already passed any delay, so the first check succeeds. */
	public Timer() {
		start = 0;
	}

	/** Starts timing from now. */
	public void reset() {
		start = now();
	}

	/** Makes the next {@link #passed} check succeed regardless of the delay. */
	public void expire() {
		start = 0;
	}

	/** Milliseconds since the last {@link #reset()} (on a monotonic clock); huge before the first reset. */
	public long elapsedMs() {
		return now() - start;
	}

	/** Whether at least {@code ms} milliseconds have passed since the last {@link #reset()}. */
	public boolean passed(long ms) {
		return elapsedMs() >= ms;
	}

	/** Whether at least {@code ms} milliseconds have passed, for delays from a decimal setting. */
	public boolean passed(double ms) {
		return elapsedMs() >= ms;
	}

	/**
	 * Whether {@code ticks} game ticks' worth of time (50 ms each) has passed. Wall-clock time: on a lagging server fewer
	 * real ticks may have gone by; count with {@link Ticks} where that matters.
	 */
	public boolean passedTicks(int ticks) {
		return passed(ticks * 50L);
	}

	/** If the delay has passed, resets and returns true: one call for "every N ms". */
	public boolean tick(long ms) {
		if (!passed(ms)) return false;
		reset();
		return true;
	}

	private static long now() {
		return System.nanoTime() / 1_000_000L;
	}
}
