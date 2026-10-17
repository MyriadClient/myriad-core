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

	public void reset() {
		start = now();
	}

	/** Makes the next {@link #passed} check succeed regardless of the delay. */
	public void expire() {
		start = 0;
	}

	public long elapsedMs() {
		return now() - start;
	}

	public boolean passed(long ms) {
		return elapsedMs() >= ms;
	}

	public boolean passed(double ms) {
		return elapsedMs() >= ms;
	}

	/** Game ticks are 50 ms apart at 20 TPS. */
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
