package dev.myriadclient.impl.event;

import dev.myriadclient.api.event.events.Render2DEvent;
import dev.myriadclient.api.event.events.Render3DEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.job.Job;
import dev.myriadclient.api.module.Module;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Times every event handler while it's installed on the bus: per owner (a module, a service, a listener object), the
 * time spent in tick handlers, render handlers and everything else over the last second, and the blocks its handlers
 * read through the level on the render thread. Each running job is timed too, under its own name. Costs two
 * {@code nanoTime} reads per handler call, so it's only on while the Profiler panel is open or {@code .profiler} asked
 * for it. Where the numbers point at a pattern core has a tool for, an owner gets a {@linkplain Entry#hint() hint}.
 */
public final class Profiler {
	private static final long WINDOW_NS = 1_000_000_000L;
	/** Render handlers taking this long a second are worth caching per tick (2 ms: a frame's share at 120 fps). */
	static final long RENDER_HINT_NS = 2_000_000;
	/** Blocks read a second through the level that are worth a chunk cache (250 a tick: a radius-3 cube). */
	static final long BLOCKS_HINT = 5_000;

	/** The thread whose block reads are counted (the render thread, while profiling), or null. */
	private static @Nullable Thread countedThread;
	/** Blocks read through the level on that thread so far; only ever written there. */
	private static long blockReads;

	/** One owner's totals: the second being counted, and the last complete second. */
	public static final class Entry {
		final Object owner;
		final String addon;
		final AtomicLong tick = new AtomicLong(), render = new AtomicLong(), other = new AtomicLong(), calls = new AtomicLong();
		final AtomicLong reads = new AtomicLong(), overBudget = new AtomicLong();
		volatile long tickNs, renderNs, otherNs, callCount, readCount, overBudgetTicks;

		Entry(Object owner, String addon) {
			this.owner = owner;
			this.addon = addon;
		}

		public String name() {
			if (owner instanceof Module m) return m.name();
			if (owner instanceof Job<?> j) return "Job " + j;
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

		/** A job's own row: its time is part of the job runner's too, so it isn't added to the total. */
		public boolean isJob() {
			return owner instanceof Job<?>;
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

		/** Blocks read a second through the level, by handlers on the render thread. */
		public long blockReads() {
			return readCount;
		}

		/**
		 * What to look at when the last second suggests a pattern core has a tool for: a job over its budget, many
		 * blocks read through the level, render handlers working out per frame what could be per tick. Null otherwise.
		 */
		public @Nullable String hint() {
			if (overBudgetTicks > 0) {
				return String.format(Locale.ROOT, "Over its tick budget on %d of %d ticks: its steps should stop when "
					+ "ctx.budget() runs out and carry on next tick", overBudgetTicks, callCount);
			}
			if (readCount >= BLOCKS_HINT) {
				return String.format(Locale.ROOT, "Reads %,d blocks a second through the level: a ChunkCache scans each "
					+ "chunk once and keeps what it found until the chunk changes", readCount);
			}
			if (renderNs >= RENDER_HINT_NS) {
				return String.format(Locale.ROOT, "%.1f ms a second in render handlers: what changes only once a tick can "
					+ "be worked out once a tick with TickCached", renderNs / 1e6);
			}
			return null;
		}
	}

	private final Map<Object, Entry> entries = new ConcurrentHashMap<>();
	private volatile long windowStart = System.nanoTime();

	/** Counts block reads on {@code thread} from now on (null: stops counting). */
	static void countBlockReadsOn(@Nullable Thread thread) {
		countedThread = thread;
	}

	/** A block was read through the level (the level's mixin calls this on every read, on every thread). */
	public static void blockRead() {
		if (countedThread == Thread.currentThread()) blockReads++;
	}

	/** Block reads counted so far, for a handler's before and after. */
	static long blockReads() {
		return blockReads;
	}

	void record(Object owner, String addon, Class<?> event, long nanos, long reads) {
		Entry e = entry(owner, addon);
		if (TickEvent.class.isAssignableFrom(event)) e.tick.addAndGet(nanos);
		else if (event == Render3DEvent.class || event == Render2DEvent.class) e.render.addAndGet(nanos);
		else e.other.addAndGet(nanos);
		e.calls.incrementAndGet();
		// Another thread's handler would see the render thread's reads in its before and after.
		if (reads > 0 && countedThread == Thread.currentThread()) e.reads.addAndGet(reads);
		roll();
	}

	/** One tick of a root job took {@code nanos}, against a budget of {@code budget}. */
	public void recordJob(Job<?> job, String addon, long nanos, long budget) {
		Entry e = entry(job, addon);
		e.tick.addAndGet(nanos);
		e.calls.incrementAndGet();
		if (nanos > budget) e.overBudget.incrementAndGet();
		roll();
	}

	private Entry entry(Object owner, String addon) {
		Entry e = entries.get(owner);
		return e != null ? e : entries.computeIfAbsent(owner, o -> new Entry(o, addon));
	}

	private void roll() {
		long now = System.nanoTime();
		if (now - windowStart >= WINDOW_NS) roll(now);
	}

	synchronized void roll(long now) {
		if (now - windowStart < WINDOW_NS) return;
		windowStart = now;
		for (Entry e : entries.values()) {
			e.tickNs = e.tick.getAndSet(0);
			e.renderNs = e.render.getAndSet(0);
			e.otherNs = e.other.getAndSet(0);
			e.callCount = e.calls.getAndSet(0);
			e.readCount = e.reads.getAndSet(0);
			e.overBudgetTicks = e.overBudget.getAndSet(0);
		}
		// A job that didn't tick in the last second has ended.
		entries.values().removeIf(e -> e.isJob() && e.callCount == 0);
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
