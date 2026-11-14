package dev.myriadclient.api.event;

/**
 * Base class for events whose default behaviour can be prevented. Each such event says what cancelling it does. Once a
 * handler cancels it, later handlers are skipped unless they {@link Subscribe#receiveCancelled() receive cancelled}
 * events, and those can see {@link #isCancelled()} and even {@linkplain #setCancelled un-cancel} it. Whoever posted the
 * event reads the flag after the last handler.
 */
public abstract class Cancellable {
	private boolean cancelled;

	/** Cancels the event: what it announces won't happen (or happens as the event says). */
	public void cancel() {
		cancelled = true;
	}

	/** Whether a handler so far cancelled it. */
	public boolean isCancelled() {
		return cancelled;
	}

	/** Sets the flag outright; {@code false} lets through an event an earlier handler cancelled. */
	public void setCancelled(boolean cancelled) {
		this.cancelled = cancelled;
	}
}
