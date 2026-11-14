package dev.myriadclient.api.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an event handler. The method must return {@code void} and take exactly one parameter, the event
 * type; it may be private. Handlers for a supertype also receive subtypes ({@code TickEvent} hears both
 * {@code TickEvent.Pre} and {@code TickEvent.Post}). Instance methods run once their object is subscribed
 * ({@link EventBus#subscribe(Object)}; a module's while it's on), static ones once their class is
 * ({@link EventBus#subscribe(Class)}).
 *
 * <pre>{@code
 * @Subscribe(inGame = true)
 * private void onTick(TickEvent.Pre event) {
 *     if (mc.player.getHealth() < 6) warn("Low health");
 * }
 *
 * // Keeps containers open on the server: the close packet never goes out.
 * @Subscribe(priority = Priority.HIGH, packets = ServerboundContainerClosePacket.class)
 * private void onClose(PacketEvent.Send event) {
 *     event.cancel();
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Subscribe {
	/** Higher runs first; equal priorities run in the order they subscribed. See {@link Priority}. */
	int priority() default Priority.NORMAL;

	/**
	 * Whether to run for an event an earlier handler {@linkplain Cancellable cancelled}. If false (default) the handler is
	 * skipped then; if true it runs and can read {@link Cancellable#isCancelled()} or un-cancel it.
	 */
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
