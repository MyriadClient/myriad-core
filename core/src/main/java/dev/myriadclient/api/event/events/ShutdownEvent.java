package dev.myriadclient.api.event.events;

/**
 * Posted once, on the render thread, when the client is stopping, before Myriad saves its config: write out anything
 * of your own here. The world may already be gone.
 */
public final class ShutdownEvent {
	/** The one instance. */
	public static final ShutdownEvent INSTANCE = new ShutdownEvent();
}
