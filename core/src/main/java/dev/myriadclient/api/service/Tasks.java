package dev.myriadclient.api.service;

import dev.myriadclient.api.util.RenderThread;
import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Work spread over game ticks: "do this in 5 ticks", "every 10 ticks", or a sequence of steps that wait for
 * conditions (open a chest, wait until it has loaded, move items, close it). Everything runs on the render thread at
 * the end of a tick. Tasks belong to an owner; a module's tasks are cancelled when it's disabled.
 *
 * <pre>{@code
 * Myriad.tasks().sequence(this)
 *     .await(() -> Myriad.containers().open(pos, 20), 40)   // waits for the future; a failure fails the sequence
 *     .require(() -> Myriad.containers().current().isPresent(), "The chest closed")
 *     .run(this::moveItems)
 *     .wait(2)
 *     .run(Myriad.containers()::close)
 *     .retry(2)                                             // start over up to twice if a step fails
 *     .onFail(t -> warn("Couldn't restock: " + t.getMessage()))
 *     .start();
 * }</pre>
 *
 * A sequence fails when a step throws, a {@link Sequence#require} check is false, an {@link Sequence#await}ed
 * future fails or a wait times out (with a {@link TimeoutException}).
 *
 * @deprecated jobs do all of this and more: {@code Myriad.jobs().later/every} for delays and repeats (they run at the
 * start of a tick, where these run at its end), and a {@code Step} started as a job for sequences, with nesting,
 * results, a reason when it's stuck, and the Jobs window. Removed in 1.0.
 */
@Deprecated
@ApiStatus.NonExtendable
@RenderThread
public interface Tasks {
	/** Runs {@code action} after {@code ticks} ticks (0 = the next time tasks run, at the end of this tick or the next). */
	Handle later(Object owner, int ticks, Runnable action);

	/** Runs {@code action} every {@code intervalTicks} ticks until cancelled. */
	Handle every(Object owner, int intervalTicks, Runnable action);

	/** Starts building a sequence of steps. Nothing runs until {@link Sequence#start()}. */
	Sequence sequence(Object owner);

	/** Cancels everything {@code owner} scheduled. */
	void cancel(Object owner);

	/** Whether {@code owner} has anything scheduled. */
	boolean isBusy(Object owner);

	@ApiStatus.NonExtendable
	interface Handle {
		void cancel();

		boolean isDone();
	}

	/** Steps run in order; each waits for the one before. */
	@ApiStatus.NonExtendable
	interface Sequence {
		/** Runs {@code action}, then moves straight on to the next step in the same tick. */
		Sequence run(Runnable action);

		/** Waits {@code ticks} ticks. */
		Sequence wait(int ticks);

		/**
		 * Waits until {@code condition} is true, checking once per tick. After {@code timeoutTicks} the sequence
		 * stops and {@link #onTimeout} runs.
		 */
		Sequence waitUntil(BooleanSupplier condition, int timeoutTicks);

		/**
		 * Starts something asynchronous when this step is reached ({@code Myriad.containers().open(...)}, a placement's
		 * {@code result()}) and waits for it. The sequence fails if the future fails or isn't done after
		 * {@code timeoutTicks}.
		 */
		Sequence await(Supplier<? extends CompletableFuture<?>> start, int timeoutTicks);

		/** Fails the sequence with {@code message} unless {@code condition} holds when this step is reached. */
		Sequence require(BooleanSupplier condition, String message);

		/**
		 * When a step fails, starts over from the first step, up to {@code times} more times, before giving up. Each
		 * attempt starts the next tick.
		 */
		Sequence retry(int times);

		/** Runs if a {@link #waitUntil} times out (before {@link #onFail}). */
		Sequence onTimeout(Runnable action);

		/** Runs once the sequence has failed for good (after any {@link #retry}), with the reason. */
		Sequence onFail(Consumer<Throwable> action);

		/** Runs after the last step. */
		Sequence onFinish(Runnable action);

		Handle start();
	}
}
