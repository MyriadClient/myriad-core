package dev.myriadclient.api.event.events;

/** The client joining and leaving a world. Posted on the render thread; not cancellable. */
public abstract class WorldEvent {
	/**
	 * The client joined a server or a singleplayer world: the player and level exist. Posted for each login the server
	 * sends, not on a dimension change (a respawn).
	 */
	public static final class Join extends WorldEvent {
		/** The one instance, posted at every join. */
		public static final Join INSTANCE = new Join();
	}

	/** The client left its world (disconnected, kicked, or quit to the title screen); every job is cancelled. */
	public static final class Leave extends WorldEvent {
		/** The one instance, posted at every leave. */
		public static final Leave INSTANCE = new Leave();
	}
}
