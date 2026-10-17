package dev.myriadclient.api.event;

/** Base class for events whose default behaviour can be prevented. */
public abstract class Cancellable {
	private boolean cancelled;

	public void cancel() {
		cancelled = true;
	}

	public boolean isCancelled() {
		return cancelled;
	}

	public void setCancelled(boolean cancelled) {
		this.cancelled = cancelled;
	}
}
