package dev.myriadclient.impl.event;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.Subscription;
import dev.myriadclient.api.event.events.Render3DEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.job.Job;
import dev.myriadclient.api.job.JobEngine;
import dev.myriadclient.api.job.Step;
import dev.myriadclient.api.job.Why;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The profiler's hints: a handler reading many blocks through the level is pointed at a chunk cache, heavy render
 * handlers at per-tick caching, a job over its budget at its budget; light ones get nothing. Block reads count only on
 * the profiled thread and inside the handler that made them.
 */
class ProfilerTest {
	private final MyriadEventBus bus = new MyriadEventBus();
	private final Profiler profiler = new Profiler();

	/** A tick handler reading {@code perTick} blocks. */
	static final class Reader {
		final int perTick;

		Reader(int perTick) {
			this.perTick = perTick;
		}

		@Subscribe
		private void onTick(TickEvent.Pre e) {
			for (int i = 0; i < perTick; i++) Profiler.blockRead();
		}
	}

	@AfterEach
	void stop() {
		bus.setProfiler(null);
	}

	private Profiler.Entry entry(Object owner) {
		for (Profiler.Entry e : profiler.snapshot()) if (e.owner == owner) return e;
		throw new AssertionError("no entry for " + owner);
	}

	private long seconds;

	/** Ends the second being counted. */
	private void nextSecond() {
		seconds += 2;
		profiler.roll(System.nanoTime() + seconds * 1_000_000_000L);
	}

	@Test
	void aHandlerReadingManyBlocksIsPointedAtAChunkCache() {
		Reader heavy = new Reader(300), light = new Reader(10);
		bus.subscribe(heavy);
		bus.subscribe(light);
		bus.setProfiler(profiler);
		for (int t = 0; t < 20; t++) bus.post(TickEvent.Pre.INSTANCE);
		// Reads outside a handler, or on another thread, belong to nobody.
		for (int i = 0; i < 10_000; i++) Profiler.blockRead();
		Thread other = new Thread(() -> {
			for (int i = 0; i < 10_000; i++) Profiler.blockRead();
		});
		other.start();
		join(other);
		nextSecond();
		assertEquals(6000, entry(heavy).blockReads());
		assertEquals(200, entry(light).blockReads());
		assertTrue(entry(heavy).hint().contains("ChunkCache"), entry(heavy).hint());
		assertNull(entry(light).hint());
	}

	@Test
	void nothingIsCountedWhileNotProfiling() {
		long before = Profiler.blockReads();
		for (int i = 0; i < 100; i++) Profiler.blockRead();
		assertEquals(before, Profiler.blockReads());
	}

	@Test
	void heavyRenderHandlersArePointedAtTickCaching() {
		Object esp = new Object(), light = new Object();
		profiler.record(esp, "test", Render3DEvent.class, 3_000_000, 0);
		profiler.record(light, "test", Render3DEvent.class, 500_000, 0);
		nextSecond();
		assertTrue(entry(esp).hint().contains("TickCached"), entry(esp).hint());
		assertNull(entry(light).hint());
	}

	@Test
	void aJobOverItsBudgetIsNamedAndNotCountedTwice() {
		JobEngine engine = new JobEngine(new Host());
		Job<?> job = engine.start("Owner", Step.until("Wait", () -> false, Integer.MAX_VALUE).named("Gather"));
		profiler.recordJob(job, "test", 3_000_000, 2_000_000);
		profiler.recordJob(job, "test", 1_000_000, 2_000_000);
		nextSecond();
		Profiler.Entry e = entry(job);
		assertTrue(e.isJob());
		assertEquals("Job " + job, e.name());
		assertTrue(e.hint().startsWith("Over its tick budget on 1 of 2 ticks"), e.hint());
		assertEquals(4.0, e.tickMs(), 1e-9);
		// Gone once it stops ticking.
		nextSecond();
		assertFalse(profiler.snapshot().stream().anyMatch(x -> x.owner == job));
	}

	private static void join(Thread t) {
		try {
			t.join();
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
	}

	private static final class Host implements JobEngine.Host {
		@Override
		public void release(Job<?> job) {
		}

		@Override
		public <E> Subscription listen(Class<E> type, Consumer<? super E> handler) {
			return () -> {
			};
		}

		@Override
		public void post(Object event) {
		}

		@Override
		public void failed(Job<?> root, Why why) {
		}

		@Override
		public void log(Job<?> job, String message) {
		}
	}
}
