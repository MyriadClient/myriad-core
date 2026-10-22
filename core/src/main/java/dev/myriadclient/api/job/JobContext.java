package dev.myriadclient.api.job;

import dev.myriadclient.api.event.Subscription;
import dev.myriadclient.api.util.TickBudget;
import org.jetbrains.annotations.ApiStatus;

import java.util.function.Consumer;

/**
 * What a {@link Step.Run} may use while it runs. Valid only inside {@code start}, {@code tick}, {@code pause},
 * {@code resume} and {@code cancel}; don't keep it.
 */
@ApiStatus.NonExtendable
public interface JobContext {
	/**
	 * The job this step runs in: pass it as the {@code owner} to every service ({@code Myriad.placement().place(ctx.job(),
	 * ...)}), so that cancelling the job cancels what the step asked for, and two jobs of one module don't cancel each
	 * other's.
	 */
	Job<?> job();

	/** Who started the root job (the module, usually): for its settings and notifications. */
	Object owner();

	/** Ticks this step has run, not counting pauses. */
	int ticks();

	/** How long this step may still work this tick: stop when it's spent and carry on next tick. */
	TickBudget budget();

	/**
	 * Hears {@code event} for the life of this step, released when the step ends whatever way. Delivered at the start of
	 * this step's next tick, on the render thread, however the event was fired: a packet received on the network thread
	 * reaches the step a tick later at most. So a step never runs off-thread and never subscribes to the bus itself;
	 * and the event has been handled by then, so a handler here reads it and can't cancel it.
	 */
	<E> Subscription on(Class<E> event, Consumer<? super E> handler);

	/** How far along, for the Jobs window's bar. */
	void progress(int done, int total);

	void progress(double fraction);

	/** A live detail for the Jobs window; overrides {@link Step.Run#status()}. Null goes back to that. */
	void status(String text);

	boolean paused();

	/** A line in the log, attributed to the job ("Printer › Build wall: ..."). */
	void log(String message);
}
