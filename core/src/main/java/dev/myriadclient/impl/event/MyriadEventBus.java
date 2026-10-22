package dev.myriadclient.impl.event;

import dev.myriadclient.api.event.Cancellable;
import dev.myriadclient.api.event.EventBus;
import dev.myriadclient.api.event.HandlerThread;
import dev.myriadclient.api.event.ListenerFlag;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.Subscription;
import dev.myriadclient.api.event.events.PacketEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Event bus in the spirit of Orbit (which Meteor uses), with the rough edges filed off:
 * <ul>
 *     <li>handlers for a supertype receive subtypes,</li>
 *     <li>a cancelled event keeps dispatching (handlers opt in with {@code receiveCancelled}),</li>
 *     <li>no per-package lambda factory registration: {@code privateLookupIn} works for every mod in Knot,</li>
 *     <li>a throwing handler is logged with its owning addon and disabled if it keeps failing,</li>
 *     <li>{@code inGame} handlers are only called with a player in a world, and packet handlers only for the packet
 *     classes they ask for (a second dispatch table per packet class, so the rest aren't even visited),</li>
 *     <li>{@link ListenerFlag}s for the hottest hooks, and an optional {@link Profiler} that times every handler,</li>
 *     <li>handlers that want the render thread get an event posted elsewhere at the start of the next tick.</li>
 * </ul>
 */
public final class MyriadEventBus implements EventBus {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/EventBus");
	private static final int MAX_FAILURES = 50;
	private static final int LOGGED_FAILURES = 3;
	private static final Listener[] EMPTY = new Listener[0];

	private final Object lock = new Object();
	/** Listeners keyed by the exact event type they declared. */
	private final Map<Class<?>, List<Listener>> byType = new HashMap<>();
	/** Listener objects (instances or classes for static handlers) -> their listeners. */
	private final Map<Object, List<Listener>> byOwner = new IdentityHashMap<>();
	/** Flattened, sorted listeners per concrete event class. Replaced wholesale on change. */
	private volatile Map<Class<?>, Listener[]> dispatch = new ConcurrentHashMap<>();
	private final Map<Method, MethodHandle> factories = new ConcurrentHashMap<>();
	private final Map<Class<?>, List<Handler>> instanceHandlers = new ConcurrentHashMap<>();
	private final Map<Class<?>, List<Handler>> staticHandlers = new ConcurrentHashMap<>();
	private long order;
	private volatile Function<Class<?>, String> ownerResolver = Class::getName;
	private volatile BooleanSupplier inGame = () -> true;
	/** Whether this is the render thread, for {@code @Subscribe(thread = RENDER)} handlers. */
	private volatile BooleanSupplier renderThread = () -> true;
	/** Events posted off the render thread, for its handlers that want them there: delivered at the next tick's start. */
	private final ConcurrentLinkedQueue<Deferred> deferred = new ConcurrentLinkedQueue<>();

	private record Deferred(Listener listener, Object event) {
	}
	private final Map<Class<?>, Flag> flags = new ConcurrentHashMap<>();
	private volatile Profiler profiler;

	/** What {@code @Subscribe(inGame = true)} checks (the client's player and level). */
	public void setInGameCheck(BooleanSupplier check) {
		inGame = check;
	}

	/** How {@code @Subscribe(thread = RENDER)} tells the render thread from others. */
	public void setRenderThreadCheck(BooleanSupplier check) {
		renderThread = check;
	}

	/**
	 * Calls the render-thread handlers of events posted elsewhere since the last call, in order: first thing in the
	 * client tick. An event cancelled by then is skipped by handlers that don't receive cancelled ones.
	 */
	public void deliverDeferred() {
		if (deferred.isEmpty()) return;
		boolean inGame = this.inGame.getAsBoolean();
		Profiler profiler = this.profiler;
		for (Deferred d; (d = deferred.poll()) != null; ) {
			Listener l = d.listener;
			if (l.disabled || l.inGame && !inGame) continue;
			if (d.event instanceof Cancellable c && c.isCancelled() && !l.receiveCancelled) continue;
			invoke(l, d.event, profiler);
		}
	}

	/** Starts or stops timing every handler (on the render thread, whose block reads are counted while it's on). */
	public void setProfiler(Profiler profiler) {
		this.profiler = profiler;
		Profiler.countBlockReadsOn(profiler != null ? Thread.currentThread() : null);
	}

	public Profiler profiler() {
		return profiler;
	}

	public void setOwnerResolver(Function<Class<?>, String> resolver) {
		this.ownerResolver = resolver;
	}

	@Override
	public void subscribe(Object listener) {
		subscribeOwner(listener, listener.getClass(), false);
	}

	@Override
	public void subscribe(Class<?> type) {
		subscribeOwner(type, type, true);
	}

	@Override
	public void unsubscribe(Object listener) {
		synchronized (lock) {
			List<Listener> ls = byOwner.remove(listener);
			if (ls == null) return;
			for (Listener l : ls) {
				// An event being dispatched right now holds its own copy of the listeners: skip this one there too.
				l.disabled = true;
				List<Listener> list = byType.get(l.type);
				if (list != null) list.remove(l);
			}
			invalidate();
		}
	}

	@Override
	public void unsubscribe(Class<?> type) {
		unsubscribe((Object) type);
	}

	@Override
	public boolean isSubscribed(Object listener) {
		synchronized (lock) {
			return byOwner.containsKey(listener);
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public <E> Subscription listen(Class<E> event, int priority, Consumer<? super E> handler) {
		Listener l = new Listener(event, priority, false, false, false, null, (Consumer<Object>) handler, ownerResolver.apply(handler.getClass()), handler);
		synchronized (lock) {
			l.order = order++;
			byType.computeIfAbsent(event, k -> new ArrayList<>()).add(l);
			invalidate();
		}
		return () -> {
			synchronized (lock) {
				l.disabled = true;
				List<Listener> list = byType.get(event);
				if (list != null && list.remove(l)) invalidate();
			}
		};
	}

	@Override
	public <E> E post(E event) {
		Listener[] listeners = event instanceof PacketEvent p ? packetListenersFor(event.getClass(), p.packet().getClass()) : listenersFor(event.getClass());
		if (listeners.length == 0) return event;
		Cancellable cancellable = event instanceof Cancellable c ? c : null;
		boolean inGame = this.inGame.getAsBoolean();
		Profiler profiler = this.profiler;
		// Asked only if a handler wants the render thread (none of the hot ones do).
		int onRender = -1;
		for (Listener l : listeners) {
			if (l.render) {
				if (onRender < 0) onRender = renderThread.getAsBoolean() ? 1 : 0;
				if (onRender == 0) {
					deferred.add(new Deferred(l, event));
					continue;
				}
			}
			if (l.disabled || l.inGame && !inGame) continue;
			if (cancellable != null && cancellable.isCancelled() && !l.receiveCancelled) continue;
			invoke(l, event, profiler);
		}
		return event;
	}

	private void invoke(Listener l, Object event, Profiler profiler) {
		long start = 0, reads = 0;
		if (profiler != null) {
			start = System.nanoTime();
			reads = Profiler.blockReads();
		}
		try {
			l.invoker.accept(event);
		} catch (Throwable t) {
			onFailure(l, event, t);
		}
		if (profiler != null) profiler.record(l.ownerObject, l.owner, l.type, System.nanoTime() - start, Profiler.blockReads() - reads);
	}

	@Override
	public boolean hasListeners(Class<?> event) {
		return listenersFor(event).length > 0;
	}

	@Override
	public ListenerFlag flag(Class<?> event) {
		return flags.computeIfAbsent(event, e -> {
			Flag f = new Flag(e);
			f.set = hasListeners(e);
			return f;
		});
	}

	private static final class Flag implements ListenerFlag {
		final Class<?> event;
		volatile boolean set;

		Flag(Class<?> event) {
			this.event = event;
		}

		@Override
		public boolean isSet() {
			return set;
		}
	}

	/** The listeners for a packet event, by the packet's class: those without a filter, plus those whose filter matches. */
	private Listener[] packetListenersFor(Class<?> eventClass, Class<?> packetClass) {
		Map<Class<?>, Map<Class<?>, Listener[]>> snapshot = packetDispatch;
		Map<Class<?>, Listener[]> perPacket = snapshot.computeIfAbsent(eventClass, k -> new ConcurrentHashMap<>());
		Listener[] cached = perPacket.get(packetClass);
		if (cached != null) return cached;
		Listener[] all = listenersFor(eventClass);
		List<Listener> out = new ArrayList<>(all.length);
		for (Listener l : all) {
			if (l.packets == null) {
				out.add(l);
				continue;
			}
			for (Class<?> c : l.packets) {
				if (c.isAssignableFrom(packetClass)) {
					out.add(l);
					break;
				}
			}
		}
		Listener[] computed = out.size() == all.length ? all : out.toArray(EMPTY);
		if (snapshot == packetDispatch) perPacket.put(packetClass, computed);
		return computed;
	}

	/** Per event class, the listeners per packet class. Replaced wholesale with {@link #dispatch}. */
	private volatile Map<Class<?>, Map<Class<?>, Listener[]>> packetDispatch = new ConcurrentHashMap<>();

	private Listener[] listenersFor(Class<?> eventClass) {
		Map<Class<?>, Listener[]> snapshot = dispatch;
		Listener[] cached = snapshot.get(eventClass);
		if (cached != null) return cached;
		synchronized (lock) {
			Listener[] computed = compute(eventClass);
			if (snapshot == dispatch) snapshot.put(eventClass, computed);
			return computed;
		}
	}

	private Listener[] compute(Class<?> eventClass) {
		List<Listener> out = new ArrayList<>();
		for (Map.Entry<Class<?>, List<Listener>> e : byType.entrySet()) {
			if (e.getKey().isAssignableFrom(eventClass)) out.addAll(e.getValue());
		}
		if (out.isEmpty()) return EMPTY;
		out.sort(Comparator.<Listener>comparingInt(l -> -l.priority).thenComparingLong(l -> l.order));
		return out.toArray(EMPTY);
	}

	private void invalidate() {
		dispatch = new ConcurrentHashMap<>();
		packetDispatch = new ConcurrentHashMap<>();
		for (Flag f : flags.values()) f.set = hasListeners(f.event);
	}

	private void subscribeOwner(Object owner, Class<?> klass, boolean statics) {
		synchronized (lock) {
			if (byOwner.containsKey(owner)) return;
		}
		List<Listener> found = new ArrayList<>();
		String ownerName = ownerResolver.apply(klass);
		for (Handler h : handlers(klass, statics)) {
			found.add(new Listener(h.event, h.priority, h.receiveCancelled, h.inGame, h.render, h.packets, createInvoker(h.method, statics ? null : owner), ownerName, owner));
		}
		synchronized (lock) {
			if (byOwner.containsKey(owner)) return;
			for (Listener l : found) {
				l.order = order++;
				byType.computeIfAbsent(l.type, k -> new ArrayList<>()).add(l);
			}
			byOwner.put(owner, found);
			if (!found.isEmpty()) invalidate();
		}
	}

	/** The @Subscribe methods of a class and its superclasses, found once per class (modules re-subscribe on every enable). */
	private List<Handler> handlers(Class<?> klass, boolean statics) {
		Map<Class<?>, List<Handler>> cache = statics ? staticHandlers : instanceHandlers;
		return cache.computeIfAbsent(klass, k -> {
			List<Handler> list = new ArrayList<>();
			for (Class<?> c = k; c != null && c != Object.class; c = c.getSuperclass()) {
				for (Method m : c.getDeclaredMethods()) {
					Subscribe sub = m.getAnnotation(Subscribe.class);
					if (sub == null) continue;
					if (Modifier.isStatic(m.getModifiers()) != statics) continue;
					if (m.getParameterCount() != 1 || m.getReturnType() != void.class || m.getParameterTypes()[0].isPrimitive()) {
						throw new IllegalArgumentException("Invalid @Subscribe method " + c.getName() + "#" + m.getName()
							+ ": must be void and take exactly one event parameter");
					}
					Class<?>[] packets = sub.packets().length == 0 ? null : sub.packets();
					if (packets != null && !PacketEvent.class.isAssignableFrom(m.getParameterTypes()[0])) {
						throw new IllegalArgumentException("@Subscribe(packets = ...) on " + c.getName() + "#" + m.getName() + " needs a PacketEvent parameter");
					}
					list.add(new Handler(m, m.getParameterTypes()[0], sub.priority(), sub.receiveCancelled(), sub.inGame(), sub.thread() == HandlerThread.RENDER, packets));
				}
			}
			return List.copyOf(list);
		});
	}

	private record Handler(Method method, Class<?> event, int priority, boolean receiveCancelled, boolean inGame, boolean render, Class<?>[] packets) {
	}

	@SuppressWarnings("unchecked")
	private Consumer<Object> createInvoker(Method method, Object instance) {
		try {
			MethodHandle factory = factories.computeIfAbsent(method, MyriadEventBus::lambdaFactory);
			return instance == null ? (Consumer<Object>) factory.invoke() : (Consumer<Object>) factory.invoke(instance);
		} catch (Throwable t) {
			// Fall back to a plain method handle; slower but works even when the lambda spin is refused.
			try {
				method.setAccessible(true);
				MethodHandle mh = MethodHandles.lookup().unreflect(method);
				MethodHandle bound = instance == null ? mh : mh.bindTo(instance);
				return event -> {
					try {
						bound.invoke(event);
					} catch (RuntimeException | Error e) {
						throw e;
					} catch (Throwable e) {
						throw new RuntimeException(e);
					}
				};
			} catch (IllegalAccessException e) {
				throw new IllegalStateException("Cannot access handler " + method, e);
			}
		}
	}

	private static MethodHandle lambdaFactory(Method method) {
		try {
			Class<?> owner = method.getDeclaringClass();
			MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
			MethodHandle target = lookup.unreflect(method);
			boolean isStatic = Modifier.isStatic(method.getModifiers());
			MethodType factoryType = isStatic ? MethodType.methodType(Consumer.class) : MethodType.methodType(Consumer.class, owner);
			CallSite site = LambdaMetafactory.metafactory(lookup, "accept", factoryType,
				MethodType.methodType(void.class, Object.class), target,
				MethodType.methodType(void.class, method.getParameterTypes()[0]));
			return site.getTarget();
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	/** Told of every handler failure (dev tooling: the self-test attributes them to the module under test). */
	private volatile BiConsumer<String, Throwable> failureHook;

	public void setFailureHook(BiConsumer<String, Throwable> hook) {
		failureHook = hook;
	}

	private void onFailure(Listener l, Object event, Throwable t) {
		var hook = failureHook;
		if (hook != null) hook.accept(l.owner + " handling " + event.getClass().getSimpleName(), t);
		int n = ++l.failures;
		if (n <= LOGGED_FAILURES) {
			LOG.error("Handler from '{}' threw while handling {}", l.owner, event.getClass().getSimpleName(), t);
		}
		if (n >= MAX_FAILURES) {
			l.disabled = true;
			LOG.error("Disabled a handler from '{}' for {} after {} failures", l.owner, l.type.getSimpleName(), n);
		}
	}

	private static final class Listener {
		final Class<?> type;
		final int priority;
		final boolean receiveCancelled, inGame;
		/** Called on the render thread only ({@code @Subscribe(thread = RENDER)}): deferred when posted elsewhere. */
		final boolean render;
		/** Packet classes this handler wants, or null for every packet. */
		final Class<?>[] packets;
		final Consumer<Object> invoker;
		/** The addon (for logs). */
		final String owner;
		/** The subscribed object or class, or the lambda (for the profiler). */
		final Object ownerObject;
		long order;
		int failures;
		volatile boolean disabled;

		Listener(Class<?> type, int priority, boolean receiveCancelled, boolean inGame, boolean render, Class<?>[] packets, Consumer<Object> invoker, String owner, Object ownerObject) {
			this.type = type;
			this.priority = priority;
			this.receiveCancelled = receiveCancelled;
			this.inGame = inGame;
			this.render = render;
			this.packets = packets;
			this.invoker = invoker;
			this.owner = owner;
			this.ownerObject = ownerObject;
		}
	}
}
