package dev.myriadclient.api.job;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

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
	/** Where a job stands. The first four are active; the last three are final. */
	enum State {
		/** Started, waiting for its first tick. */
		QUEUED,
		/** Working. */
		RUNNING,
		/** Waiting for a reason that may clear; see {@link #why()}. */
		STUCK,
		/** Paused by the player or {@link #pause()}: not ticked until resumed. */
		PAUSED,
		/** Finished; {@link #result()} has completed with the result. */
		DONE,
		/** Couldn't finish; {@link #why()} says why. */
		FAILED,
		/** Ended from outside: the player, a parent failing, a lost race, the owner turning off, leaving the world. */
		CANCELLED;

		/** Queued, running, stuck or paused: not over. */
		public boolean isActive() {
			return this == QUEUED || this == RUNNING || this == STUCK || this == PAUSED;
		}
	}

	/** Unique while the game runs; what {@code .job cancel <id>} takes. */
	int id();

	/** The step's name, as the Jobs window shows it (a live name from {@code Step.named(Supplier)} as of now). */
	String name();

	/** Who started the root: a module, usually. */
	Object owner();

	/** The job this one runs under, or null for a root job (one {@link Jobs#start} made). */
	@Nullable Job<?> parent();

	/** The jobs under this one that are running now. */
	List<Job<?>> children();

	/** The step this job runs, as it was built. */
	Step<T> step();

	/** Where it stands now. */
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

	/** Undoes {@link #pause()}: it's ticked again from the next job tick, and running steps are told. */
	void resume();

	/** Cancels this job and everything under it: children first, then what each held. */
	void cancel();

	/** A failed or cancelled job's reason, as an exception for {@link #result()}. */
	final class JobException extends RuntimeException {
		/** Why the job failed or was cancelled. */
		private final Why why;

		/** Core's; only Myriad makes these. */
		@ApiStatus.Internal
		public JobException(Why why) {
			super(why.toString(), why.cause());
			this.why = why;
		}

		/** The reason the job failed or was cancelled. */
		public Why why() {
			return why;
		}
	}
}
