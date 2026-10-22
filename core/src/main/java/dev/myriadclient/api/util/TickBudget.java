package dev.myriadclient.api.util;

/**
 * How long a piece of incremental work may still run this tick: "as much as fits in 2 ms, then carry on next tick".
 * Jobs get one from {@code JobContext.budget()}; a loop of your own can make one.
 *
 * <pre>{@code
 * TickBudget budget = TickBudget.of(2_000_000);   // 2 ms
 * while (budget.hasTime() && !queue.isEmpty()) work(queue.poll());
 * }</pre>
 *
 * Not thread-safe: one per loop.
 */
public final class TickBudget {
	private final long nanos;
	private long deadline;

	private TickBudget(long nanos) {
		this.nanos = Math.max(0, nanos);
		reset();
	}

	/** A budget of {@code nanos}, starting now. */
	public static TickBudget of(long nanos) {
		return new TickBudget(nanos);
	}

	/** Whether there's time left; cheap enough to ask every iteration (one clock read). */
	public boolean hasTime() {
		return System.nanoTime() < deadline;
	}

	public long remainingNanos() {
		return Math.max(0, deadline - System.nanoTime());
	}

	/** Starts the clock again (the job runner does this before each job's tick). */
	public void reset() {
		deadline = System.nanoTime() + nanos;
	}
}
