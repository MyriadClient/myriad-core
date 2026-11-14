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

	/** Client ticks since the game started (20 a second while the game runs at full speed). */
	public static long current() {
		return current;
	}

	/** Client ticks since {@code tick}, a value read from {@link #current()} earlier; negative if it's in the future. */
	public static long since(long tick) {
		return current - tick;
	}

	/** Core's: counts one tick, called once at the start of every client tick. */
	@ApiStatus.Internal
	public static void advance() {
		current++;
	}
}
