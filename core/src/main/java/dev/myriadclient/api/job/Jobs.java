package dev.myriadclient.api.job;

import dev.myriadclient.api.annotation.RenderThread;
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
 * on its step) shows a notice with the owner's name and the reason. The Jobs window lists every job, and the player
 * can pause or cancel any of them there.
 *
 * <pre>{@code
 * // In a module: restock when low, one job at a time; turning the module off cancels it.
 * if (!Myriad.jobs().isBusy(this) && lowOnFood()) {
 *     Myriad.jobs().start(this, restock).result().thenAccept(moved -> info("Took " + moved));
 * }
 *
 * // A one-off delay without a Step of your own.
 * Myriad.jobs().later(this, 20, () -> info("A second later"));
 * }</pre>
 */
@ApiStatus.NonExtendable
@RenderThread
public interface Jobs {
	/**
	 * Starts {@code step} for {@code owner} (your module, usually: see {@link #cancel}) as a new root job and returns it.
	 * It first ticks at the coming job tick (this tick's, if that hasn't run yet; the next one when started from a step
	 * already running). One step value can be started any number of times, at once too.
	 */
	<T> Job<T> start(Object owner, Step<T> step);

	/** Every active root job, oldest first. */
	List<Job<?>> running();

	/** {@code owner}'s active root jobs, oldest first (compared by identity). */
	List<Job<?>> of(Object owner);

	/** An active job by id, root or not; empty once it has ended. */
	Optional<Job<?>> byId(int id);

	/**
	 * Cancels every root job {@code owner} started, with everything under it: with {@link Why#OWNER_OFF} when the owner is
	 * a module that's off, else {@link Why#CANCELLED}. Core calls this when a module turns off.
	 */
	void cancel(Object owner);

	/** Whether {@code owner} has an active root job. */
	boolean isBusy(Object owner);

	/**
	 * Runs {@code action} after {@code ticks} ticks (0 or less: at the coming job tick), at the start of that tick, as a
	 * job of {@code owner}'s; cancel the job to call it off.
	 */
	Job<Void> later(Object owner, int ticks, Runnable action);

	/**
	 * Runs {@code action} at the coming job tick and then every {@code intervalTicks} ticks (at least 1), as a job of
	 * {@code owner}'s that never ends on its own: cancel it (or turn the owning module off) to stop it.
	 */
	Job<Void> every(Object owner, int intervalTicks, Runnable action);
}
