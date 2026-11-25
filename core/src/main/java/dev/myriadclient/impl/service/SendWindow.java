package dev.myriadclient.impl.service;

import dev.myriadclient.impl.util.Clock;

/**
 * Packets sent in the last {@code windowMs}, counted from each one's exact send time: one drops out exactly a window
 * after it went, never early (as a bucketed count's do, up to a bucket ahead). Holds the last {@link #CAPACITY} send
 * times; more than that in one window counts as {@link #CAPACITY}, far past any budget. Any thread.
 */
final class SendWindow {
	static final int CAPACITY = 512;

	private final long[] times = new long[CAPACITY];
	private final int windowMs;
	/** Sends recorded so far; the newest is at {@code (sent - 1) % CAPACITY}. */
	private long sent;

	SendWindow(int windowMs) {
		this.windowMs = windowMs;
	}

	int windowMs() {
		return windowMs;
	}

	synchronized void record() {
		times[(int) (sent++ % CAPACITY)] = Clock.millis();
	}

	/** Takes back the newest send: it was counted, then went out as something else. */
	synchronized void unrecord() {
		if (sent > 0) sent--;
	}

	/** How many went out in the last window, newest first until one is older than the window. */
	synchronized int count() {
		long now = Clock.millis();
		int n = 0;
		for (long i = sent - 1; i >= 0 && i >= sent - CAPACITY; i--) {
			long age = now - times[(int) (i % CAPACITY)];
			// (A send stamped ahead of now, the wall clock having gone back, counts until it's a window ahead.)
			if (age >= windowMs || age <= -windowMs) break;
			n++;
		}
		return n;
	}
}
