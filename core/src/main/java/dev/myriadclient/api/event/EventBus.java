package dev.myriadclient.api.event;

import org.jetbrains.annotations.ApiStatus;

import java.util.function.Consumer;

@ApiStatus.NonExtendable
public interface EventBus {
	/** Subscribes every {@link Subscribe} method on {@code listener} (including inherited ones). */
	void subscribe(Object listener);

	/** Subscribes static {@link Subscribe} methods of {@code type}. */
	void subscribe(Class<?> type);

	void unsubscribe(Object listener);

	void unsubscribe(Class<?> type);

	boolean isSubscribed(Object listener);

	default <E> Subscription listen(Class<E> event, Consumer<? super E> handler) {
		return listen(event, Priority.NORMAL, handler);
	}

	<E> Subscription listen(Class<E> event, int priority, Consumer<? super E> handler);

	/** Dispatches {@code event} to every listener of its class or any supertype. Returns the event. */
	<E> E post(E event);

	/** True if anything listens for {@code event} or a supertype (to skip allocating unwatched events). */
	boolean hasListeners(Class<?> event);

	/**
	 * A flag that stays equal to {@link #hasListeners} for {@code event}, kept current as listeners come and go: one
	 * volatile read for hooks that run thousands of times a tick (collision shapes, block render shapes), where even a
	 * map lookup is too much.
	 */
	ListenerFlag flag(Class<?> event);
}
