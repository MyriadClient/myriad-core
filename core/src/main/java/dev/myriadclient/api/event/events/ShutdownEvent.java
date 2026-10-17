package dev.myriadclient.api.event.events;

/** Fired once when the client is stopping, before Myriad saves its config. */
public final class ShutdownEvent {
	public static final ShutdownEvent INSTANCE = new ShutdownEvent();
}
