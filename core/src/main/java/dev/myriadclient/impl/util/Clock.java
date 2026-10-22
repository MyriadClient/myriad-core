package dev.myriadclient.impl.util;

import java.util.function.LongSupplier;

/**
 * The time the services act by (breaks, swaps, glide starts, waits on the server): the wall clock in game, a clock
 * the test harness moves 50 ms a tick in tests, so timing rules can be checked without waiting in real time.
 */
public final class Clock {
	private static volatile LongSupplier millis = System::currentTimeMillis;

	private Clock() {
	}

	/** Milliseconds, as {@link System#currentTimeMillis()} in game. */
	public static long millis() {
		return millis.getAsLong();
	}

	/** Puts the services on {@code source} instead of the wall clock; null goes back to it. For the test harness only. */
	public static void use(LongSupplier source) {
		millis = source == null ? System::currentTimeMillis : source;
	}
}
