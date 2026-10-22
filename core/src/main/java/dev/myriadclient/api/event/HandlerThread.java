package dev.myriadclient.api.event;

/** Which thread a {@link Subscribe} handler is called on. */
public enum HandlerThread {
	/** The thread the event is posted on, at once: the render thread, except for events marked {@code @AnyThread}. */
	POSTER,
	/**
	 * The render thread: at once when the event is posted there, otherwise at the start of the next tick. A late
	 * handler can't cancel or change the event (that's been decided by then) and skips it if it was cancelled, unless
	 * it {@link Subscribe#receiveCancelled() receives cancelled} ones. For handlers of a {@code PacketEvent.Receive}
	 * (network thread) that only read the packet and act on the game.
	 */
	RENDER
}
