package dev.myriadclient.api.event.events;

/** Fired around every client tick. Not fired while no world is loaded unless noted. */
public abstract class TickEvent {
	/** Start of {@code MinecraftClient#tick}. Fired even on menus. */
	public static final class Pre extends TickEvent {
		public static final Pre INSTANCE = new Pre();
	}

	/** End of {@code MinecraftClient#tick}. Fired even on menus. */
	public static final class Post extends TickEvent {
		public static final Post INSTANCE = new Post();
	}
}
