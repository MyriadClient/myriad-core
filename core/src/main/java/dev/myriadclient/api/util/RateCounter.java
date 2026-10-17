package dev.myriadclient.api.util;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Counts events over the last second: clicks per second, packets per second, placements per second. Lock-free, so it
 * can be bumped from the network thread for every packet: the window is split into buckets that are reset as the
 * clock reaches them again.
 */
public final class RateCounter {
	private static final int BUCKETS = 20;

	private final long bucketMs;
	private final AtomicLongArray counts = new AtomicLongArray(BUCKETS);
	private final AtomicLongArray stamps = new AtomicLongArray(BUCKETS);

	public RateCounter() {
		this(1000);
	}

	public RateCounter(long windowMs) {
		this.bucketMs = Math.max(1, windowMs / BUCKETS);
	}

	public void record() {
		long slot = now() / bucketMs;
		int i = (int) (slot % BUCKETS);
		long seen = stamps.get(i);
		// The first to reach a bucket in a new lap clears it (an increment racing the clear may be lost: fine).
		if (seen != slot && stamps.compareAndSet(i, seen, slot)) counts.set(i, 0);
		counts.incrementAndGet(i);
	}

	/** Events in the last window (a second by default). */
	public int count() {
		long slot = now() / bucketMs;
		long n = 0;
		for (int i = 0; i < BUCKETS; i++) {
			if (slot - stamps.get(i) < BUCKETS) n += counts.get(i);
		}
		return (int) n;
	}

	public void clear() {
		for (int i = 0; i < BUCKETS; i++) {
			stamps.set(i, Long.MIN_VALUE);
			counts.set(i, 0);
		}
	}

	private static long now() {
		return System.nanoTime() / 1_000_000L;
	}
}
