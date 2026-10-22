package dev.myriadclient.api.job;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Optional;

/**
 * Runs steps as jobs ({@code Myriad.jobs()}). One runner ticks every job once per tick in {@code TickEvent.Pre} at
 * {@link dev.myriadclient.api.event.Priority#JOBS}, before the services send this tick's actions, so what a step asks
 * for goes out in that tick; jobs tick only in game. A job belongs to its owner: turning the owning module off cancels
 * its jobs ({@link Why#OWNER_OFF}); leaving the world cancels every job ({@link Why#LEFT_WORLD}). Render thread only.
 * <p>
 * A root job that fails with no one listening to its {@link Job#result()} (and no {@code onFailed} or {@code recover}
 * on its step) shows a notice with the owner's name and the reason.
 */
@ApiStatus.NonExtendable
public interface Jobs {
	/** Starts {@code step} for {@code owner}. It first ticks at the coming job tick (this tick's, if that hasn't run yet). */
	<T> Job<T> start(Object owner, Step<T> step);

	/** Every active root job, oldest first. */
	List<Job<?>> running();

	/** {@code owner}'s active root jobs. */
	List<Job<?>> of(Object owner);

	/** An active job by id, root or not. */
	Optional<Job<?>> byId(int id);

	/** Cancels every job {@code owner} started. */
	void cancel(Object owner);

	/** Whether {@code owner} has an active job. */
	boolean isBusy(Object owner);

	// The conveniences Tasks had, on the job runner: at the start of a tick (Tasks ran at its end).

	/** Runs {@code action} after {@code ticks} ticks. */
	Job<Void> later(Object owner, int ticks, Runnable action);

	/** Runs {@code action} now and then every {@code intervalTicks} ticks (at least 1) until cancelled. */
	Job<Void> every(Object owner, int intervalTicks, Runnable action);
}
