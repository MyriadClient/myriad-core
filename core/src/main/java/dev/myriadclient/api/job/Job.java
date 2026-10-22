package dev.myriadclient.api.job;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A step being run (or finished): what {@link Jobs#start} hands back, what the Jobs window lists, what another addon
 * sees through {@link JobEvent}. A job's children are the steps under it that are running now: a sequence has one at a
 * time, {@code all} and {@code race} several, and a {@code whenStuck} remedy shows as a child of the step it helps.
 * Policies ({@code retry}, {@code timeout}...) belong to the job of the step they wrap, so a job stays the same job
 * across a retry.
 *
 * <pre>{@code
 * Job<Integer> job = Myriad.jobs().start(this, restock);
 * job.result().thenAccept(moved -> info("Took " + moved))
 *     .exceptionally(t -> { warn("Restock failed: " + Why.of(t)); return null; });
 * }</pre>
 *
 * Its {@code toString()} is "Owner › path › name", which is how services that print their owner show a job.
 */
@ApiStatus.NonExtendable
public interface Job<T> {
	enum State {
		/** Started, waiting for its first tick. */
		QUEUED,
		RUNNING,
		/** Waiting for a reason that may clear; see {@link #why()}. */
		STUCK,
		PAUSED,
		DONE,
		FAILED,
		CANCELLED;

		/** Queued, running, stuck or paused: not over. */
		public boolean isActive() {
			return this == QUEUED || this == RUNNING || this == STUCK || this == PAUSED;
		}
	}

	/** Unique while the game runs; what {@code .job cancel <id>} takes. */
	int id();

	String name();

	/** Who started the root: a module, usually. */
	Object owner();

	@Nullable Job<?> parent();

	/** The jobs under this one that are running now. */
	List<Job<?>> children();

	/** The step this job runs, as it was built. */
	Step<T> step();

	State state();

	/** The nearest reason when stuck, failed or cancelled (its own, or the child's it's waiting on); null otherwise. */
	@Nullable Why why();

	/** The live detail from the running step ({@link Step.Run#status()} or {@link JobContext#status}), or null. */
	@Nullable String status();

	/** 0..1, from {@link JobContext#progress} or a sequence's place in its steps; NaN when nothing says. */
	double progress();

	/** Ticks it has run, not counting pauses. */
	int ticks();

	/** Its policies as people read them: "retry 1/3", "timeout 400". */
	List<String> pills();

	/**
	 * Completes with the result when done. Fails with a {@link JobException} carrying the {@link Why} when the job
	 * fails or is cancelled ({@link Why#of(Throwable)} reads it back through a dependent stage). Completes on the render
	 * thread.
	 */
	CompletableFuture<T> result();

	/** Stops ticking it; running steps are told ({@link Step.Run#pause}). The whole subtree pauses. */
	void pause();

	void resume();

	/** Cancels this job and everything under it: children first, then what each held. */
	void cancel();

	/** A failed or cancelled job's reason, as an exception for {@link #result()}. */
	final class JobException extends RuntimeException {
		private final Why why;

		@ApiStatus.Internal
		public JobException(Why why) {
			super(why.toString(), why.cause());
			this.why = why;
		}

		public Why why() {
			return why;
		}
	}
}
