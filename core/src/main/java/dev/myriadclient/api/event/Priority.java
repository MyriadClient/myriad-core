package dev.myriadclient.api.event;

/**
 * Common handler priorities, for {@link Subscribe#priority()} and
 * {@link EventBus#listen(Class, int, java.util.function.Consumer)}. Any int works; higher runs first, and handlers of
 * equal priority run in the order they subscribed. Core's own services handle {@code TickEvent.Pre} far above these
 * ({@link #BEFORE_ACTIONS}, {@link #JOBS}), so an addon's handlers at the usual levels run after them.
 */
public final class Priority {
	/** Before almost every other handler: to see an event first, or to cancel it before others act on it. */
	public static final int HIGHEST = 200;
	/** Before the default. */
	public static final int HIGH = 100;
	/** The default. */
	public static final int NORMAL = 0;
	/** After the default: to react to what other handlers left. */
	public static final int LOW = -100;
	/** After almost every other handler: to have the last word on what an event carries. */
	public static final int LOWEST = -200;
	/**
	 * For a {@code TickEvent.Pre} handler that has to reach the server before any of this tick's actions: core sends
	 * actions held over from the end of the last tick, and its own, after it. Answers to the server's pings held back
	 * meanwhile, for one, must go first: Grim flags an action that comes after a movement packet but before an answer.
	 */
	public static final int BEFORE_ACTIONS = 1100;
	/**
	 * Where jobs tick ({@code Myriad.jobs()}): after actions held over from the last tick have gone out (1000), before
	 * the services send this tick's (inventory 960, rotations 900, placement 890, breaking 880), so what a step asks
	 * for goes out in this tick's packets.
	 */
	public static final int JOBS = 990;

	private Priority() {
	}
}
