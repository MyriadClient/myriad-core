package dev.myriadclient.api.util;

import org.jetbrains.annotations.ApiStatus;

/**
 * The client tick counter: one more every game tick, from when the game started, whatever world you're in. For "once
 * per tick" caches ({@link TickCached}) and for timing in ticks without a {@link Timer} per module.
 */
public final class Ticks {
	private static volatile long current;

	private Ticks() {
	}

	/** Ticks since the game started. */
	public static long current() {
		return current;
	}

	/** Ticks since {@code tick}. */
	public static long since(long tick) {
		return current - tick;
	}

	@ApiStatus.Internal
	public static void advance() {
		current++;
	}
}
