package dev.myriadclient.api.event.events;

/**
 * Posted around every client tick (20 a second, unless the game lags), on the render thread, in the menus as well as in
 * a world: use {@code @Subscribe(inGame = true)} for handlers that need the player. Subscribe to {@code TickEvent}
 * itself to hear both. Not cancellable.
 */
public abstract class TickEvent {
	/**
	 * The start of the client tick, before the player, the world and the input are ticked. Core's services (job runner,
	 * inventory, rotations, placement, breaking) handle it at high priorities ({@code Priority.JOBS} and above), so what
	 * a handler at the usual priorities asks of them goes out in the next tick's packets.
	 */
	public static final class Pre extends TickEvent {
		/** The one instance, posted every tick. */
		public static final Pre INSTANCE = new Pre();
	}

	/** The end of the client tick, after the player and the world have ticked and this tick's movement was sent. */
	public static final class Post extends TickEvent {
		/** The one instance, posted every tick. */
		public static final Post INSTANCE = new Post();
	}
}
