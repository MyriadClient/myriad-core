package dev.myriadclient.api.event;

import org.jetbrains.annotations.ApiStatus;

/** Handle returned by {@link EventBus#listen}; call {@link #unsubscribe()} to stop listening. */
@FunctionalInterface
@ApiStatus.NonExtendable
public interface Subscription {
	/** Stops the handler, at once (an event being delivered right now skips it too). Calling it again does nothing. */
	void unsubscribe();
}
