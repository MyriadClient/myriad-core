package dev.myriadclient.api.event;

import org.jetbrains.annotations.ApiStatus;

/** Whether anything listens for an event, kept current by the bus; see {@link EventBus#flag}. */
@ApiStatus.NonExtendable
public interface ListenerFlag {
	/** Whether at least one handler listens for the event (or a supertype) right now. Safe on any thread. */
	boolean isSet();
}
