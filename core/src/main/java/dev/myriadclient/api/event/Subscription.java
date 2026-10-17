package dev.myriadclient.api.event;

import org.jetbrains.annotations.ApiStatus;
/** Handle returned by {@link EventBus#listen}; call {@link #unsubscribe()} to stop listening. */
@FunctionalInterface
@ApiStatus.NonExtendable
public interface Subscription {
	void unsubscribe();
}
