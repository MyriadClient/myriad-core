package dev.myriadclient.api.event.events;

/** World lifecycle. */
public abstract class WorldEvent {
	/** The client joined a world/server (the player exists). */
	public static final class Join extends WorldEvent {
		public static final Join INSTANCE = new Join();
	}

	/** The client left its world. */
	public static final class Leave extends WorldEvent {
		public static final Leave INSTANCE = new Leave();
	}
}
