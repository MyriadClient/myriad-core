package dev.myriadclient.impl.event;

import dev.myriadclient.api.event.events.Render2DEvent;
import dev.myriadclient.api.event.events.Render3DEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Module;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Times every event handler while it's installed on the bus: per owner (a module, a service, a listener object), the
 * time spent in tick handlers, render handlers and everything else over the last second. Costs two {@code nanoTime}
 * reads per handler call, so it's only on while the Profiler panel is open or {@code .profiler} asked for it.
 */
public final class Profiler {
	private static final long WINDOW_NS = 1_000_000_000L;

	/** One owner's totals: the second being counted, and the last complete second. */
	public static final class Entry {
		final Object owner;
		final String addon;
		final AtomicLong tick = new AtomicLong(), render = new AtomicLong(), other = new AtomicLong(), calls = new AtomicLong();
		volatile long tickNs, renderNs, otherNs, callCount;

		Entry(Object owner, String addon) {
			this.owner = owner;
			this.addon = addon;
		}

		public String name() {
			if (owner instanceof Module m) return m.name();
			if (owner instanceof Class<?> c) return c.getSimpleName();
			String n = owner.getClass().getName();
			int lambda = n.indexOf("$$Lambda");
			// A lambda from Myriad.events().listen(...): name it by the class that made it.
			if (lambda > 0) n = n.substring(0, lambda);
			n = n.substring(n.lastIndexOf('.') + 1).replace('$', '.');
			return n.isEmpty() ? "listener in " + addon : n + " listener";
		}

		public String addon() {
			return addon;
		}

		/** Milliseconds per second spent in tick handlers. */
		public double tickMs() {
			return tickNs / 1e6;
		}

		public double renderMs() {
			return renderNs / 1e6;
		}

		public double otherMs() {
			return otherNs / 1e6;
		}

		public double totalMs() {
			return (tickNs + renderNs + otherNs) / 1e6;
		}

		public long calls() {
			return callCount;
		}
	}

	private final Map<Object, Entry> entries = new ConcurrentHashMap<>();
	private volatile long windowStart = System.nanoTime();

	void record(Object owner, String addon, Class<?> event, long nanos) {
		Entry e = entries.get(owner);
		if (e == null) e = entries.computeIfAbsent(owner, o -> new Entry(o, addon));
		if (TickEvent.class.isAssignableFrom(event)) e.tick.addAndGet(nanos);
		else if (event == Render3DEvent.class || event == Render2DEvent.class) e.render.addAndGet(nanos);
		else e.other.addAndGet(nanos);
		e.calls.incrementAndGet();
		long now = System.nanoTime();
		if (now - windowStart >= WINDOW_NS) roll(now);
	}

	private synchronized void roll(long now) {
		if (now - windowStart < WINDOW_NS) return;
		windowStart = now;
		for (Entry e : entries.values()) {
			e.tickNs = e.tick.getAndSet(0);
			e.renderNs = e.render.getAndSet(0);
			e.otherNs = e.other.getAndSet(0);
			e.callCount = e.calls.getAndSet(0);
		}
	}

	/** The last complete second, heaviest first. */
	public List<Entry> snapshot() {
		List<Entry> out = new ArrayList<>(entries.values());
		out.sort(Comparator.comparingDouble(Entry::totalMs).reversed());
		return out;
	}

	public void clear() {
		entries.clear();
	}
}
