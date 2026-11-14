package dev.myriadclient.api.event;

import dev.myriadclient.api.annotation.AnyThread;
import org.jetbrains.annotations.ApiStatus;

import java.util.function.Consumer;

/**
 * Myriad's event bus ({@code Myriad.events()}): delivers each posted event to every handler of its class or a
 * supertype, highest {@link Priority} first and, among equals, in the order they subscribed.
 * <p>
 * Handlers are {@link Subscribe} methods on an object or class you subscribe, or lambdas from {@link #listen}. A
 * module's {@code @Subscribe} methods are subscribed for you while it's on. A handler that throws is logged with the
 * addon it belongs to and the event goes on to the next; one that keeps throwing (50 times) is turned off for the
 * session.
 * <p>
 * Handlers run on the thread the event is posted on: the render thread, except for events marked {@link AnyThread}
 * (see {@link Subscribe#thread()} to get those on the render thread). Every method here may be called from any thread.
 *
 * <pre>{@code
 * // A helper that isn't a module: subscribed and unsubscribed by hand.
 * Myriad.events().subscribe(tracker);
 * ...
 * Myriad.events().unsubscribe(tracker);
 *
 * // A one-off lambda, released through its Subscription.
 * Subscription sub = Myriad.events().listen(WorldEvent.Leave.class, e -> forgetEverything());
 * ...
 * sub.unsubscribe();
 *
 * // An event of your own, for other addons to hear.
 * public record StashFound(BlockPos pos, int chests) {}
 * Myriad.events().post(new StashFound(pos, chests));
 * }</pre>
 */
@ApiStatus.NonExtendable
@AnyThread
public interface EventBus {
	/**
	 * Subscribes every non-static {@link Subscribe} method on {@code listener}, including those its superclasses
	 * declare (private ones too). Subscribing an object that is already subscribed does nothing.
	 *
	 * @throws IllegalArgumentException if a {@code @Subscribe} method doesn't return {@code void} and take exactly one
	 *                                  (non-primitive) parameter, or asks for {@link Subscribe#packets()} without
	 *                                  taking a {@code PacketEvent}
	 */
	void subscribe(Object listener);

	/** Subscribes the static {@link Subscribe} methods of {@code type} and its superclasses; as {@link #subscribe(Object)}. */
	void subscribe(Class<?> type);

	/**
	 * Removes every handler {@link #subscribe(Object)} added for {@code listener}, at once: an event being delivered
	 * right now skips them too. Does nothing if it isn't subscribed.
	 */
	void unsubscribe(Object listener);

	/** Removes the static handlers {@link #subscribe(Class)} added for {@code type}. */
	void unsubscribe(Class<?> type);

	/** Whether {@code listener} (an object, or a class subscribed for its static handlers) is subscribed now. */
	boolean isSubscribed(Object listener);

	/** {@link #listen(Class, int, Consumer)} at {@link Priority#NORMAL}. */
	default <E> Subscription listen(Class<E> event, Consumer<? super E> handler) {
		return listen(event, Priority.NORMAL, handler);
	}

	/**
	 * Calls {@code handler} for every {@code event} (and subclass) posted, until the returned {@link Subscription} is
	 * released. It runs on the posting thread, also when not in game, and is skipped for an event already cancelled.
	 *
	 * @param priority higher runs first; see {@link Priority}
	 */
	<E> Subscription listen(Class<E> event, int priority, Consumer<? super E> handler);

	/**
	 * Delivers {@code event} to every handler of its class or a supertype, on this thread, and returns it, so the
	 * poster can read what handlers changed ({@code post(new MyEvent()).isCancelled()}). Handlers that asked for the
	 * render thread get an event posted elsewhere at the start of the next tick instead.
	 */
	<E> E post(E event);

	/**
	 * Whether anything listens for {@code event} or a supertype: check it before building an event nobody would hear.
	 */
	boolean hasListeners(Class<?> event);

	/**
	 * A flag that stays equal to {@link #hasListeners} for {@code event}, kept current as listeners come and go: one
	 * volatile read for hooks that run thousands of times a tick (collision shapes, block render shapes), where even a
	 * map lookup is too much. The same flag comes back for the same class.
	 */
	ListenerFlag flag(Class<?> event);
}
