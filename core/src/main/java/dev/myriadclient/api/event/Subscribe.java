package dev.myriadclient.api.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an event handler. The method must return {@code void} and take exactly one parameter,
 * the event type. Handlers for a supertype also receive subtypes.
 *
 * <pre>{@code
 * @Subscribe
 * private void onTick(TickEvent.Pre event) { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Subscribe {
	/** Higher runs first. See {@link Priority}. */
	int priority() default Priority.NORMAL;

	/** If false (default) the handler is skipped once an earlier handler cancelled the event. */
	boolean receiveCancelled() default false;

	/**
	 * Only call the handler while there's a player in a world, so it needn't check. Most handlers that touch the
	 * player or the level want this; the few that run on the title screen (rendering a HUD preview, a world join)
	 * don't.
	 */
	boolean inGame() default false;

	/**
	 * For {@code PacketEvent} handlers: only the packets of these classes (or subclasses). The bus then skips the
	 * handler for every other packet, cheaper than an {@code instanceof} in a handler that runs for every packet.
	 */
	Class<?>[] packets() default {};

	/**
	 * The thread the handler runs on: where the event is posted (default), or the render thread, for a handler of an
	 * event posted elsewhere ({@code PacketEvent.Receive}) that acts on the game. See {@link HandlerThread#RENDER}.
	 */
	HandlerThread thread() default HandlerThread.POSTER;
}
