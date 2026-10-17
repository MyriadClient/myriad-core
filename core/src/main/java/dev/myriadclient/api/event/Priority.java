package dev.myriadclient.api.event;

/** Common handler priorities. Any int works; higher runs first. */
public final class Priority {
	public static final int HIGHEST = 200;
	public static final int HIGH = 100;
	public static final int NORMAL = 0;
	public static final int LOW = -100;
	public static final int LOWEST = -200;
	/**
	 * For a {@code TickEvent.Pre} handler that has to reach the server before any of this tick's actions: core sends
	 * actions held over from the end of the last tick, and its own, after it. Answers to the server's pings held back
	 * meanwhile, for one, must go first: Grim flags an action that comes after a movement packet but before an answer.
	 */
	public static final int BEFORE_ACTIONS = 1100;

	private Priority() {
	}
}
