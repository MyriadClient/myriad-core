package dev.myriadclient.impl.service;

import dev.myriadclient.api.service.Tasks;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class TaskSchedulerTest {
	@Test
	void laterRunsAfterTheGivenTicks() {
		TaskScheduler s = new TaskScheduler();
		List<String> log = new ArrayList<>();
		s.later(this, 2, () -> log.add("ran"));
		s.tick(); // the first tasks run: the wait starts
		assertTrue(log.isEmpty());
		s.tick();
		assertTrue(log.isEmpty());
		s.tick();
		assertEquals(List.of("ran"), log);
		assertFalse(s.isBusy(this));
	}

	@Test
	void sequenceRunsStepsInOrderAndWaitsForConditions() {
		TaskScheduler s = new TaskScheduler();
		List<String> log = new ArrayList<>();
		AtomicBoolean ready = new AtomicBoolean();
		s.sequence(this).run(() -> log.add("open")).waitUntil(ready::get, 10).run(() -> log.add("move")).wait(1).run(() -> log.add("close"))
			.onFinish(() -> log.add("done")).start();
		s.tick();
		assertEquals(List.of("open"), log);
		s.tick();
		assertEquals(List.of("open"), log);
		ready.set(true);
		s.tick(); // condition met: move, then start waiting one tick
		assertEquals(List.of("open", "move"), log);
		s.tick();
		assertEquals(List.of("open", "move", "close", "done"), log);
	}

	@Test
	void waitUntilTimesOut() {
		TaskScheduler s = new TaskScheduler();
		List<String> log = new ArrayList<>();
		s.sequence(this).waitUntil(() -> false, 3).run(() -> log.add("never")).onTimeout(() -> log.add("timeout")).start();
		for (int i = 0; i < 5; i++) s.tick();
		assertEquals(List.of("timeout"), log);
	}

	@Test
	void cancellingAnOwnerStopsItsTasks() {
		TaskScheduler s = new TaskScheduler();
		List<String> log = new ArrayList<>();
		Object other = new Object();
		Tasks.Handle h = s.every(this, 1, () -> log.add("mine"));
		s.every(other, 1, () -> log.add("other"));
		s.tick();
		s.tick();
		s.cancel(this);
		assertTrue(h.isDone());
		s.tick();
		assertEquals(List.of("mine", "other", "mine", "other", "other"), log);
		assertTrue(s.isBusy(other));
	}

	@Test
	void aFailingTaskIsDroppedWithoutStoppingOthers() {
		TaskScheduler s = new TaskScheduler();
		List<String> log = new ArrayList<>();
		s.later(this, 0, () -> {
			throw new RuntimeException("boom");
		});
		s.later(this, 0, () -> log.add("fine"));
		s.tick();
		s.tick();
		assertEquals(List.of("fine"), log);
	}

	@Test
	void awaitWaitsForTheFutureThenContinues() {
		TaskScheduler s = new TaskScheduler();
		List<String> log = new ArrayList<>();
		CompletableFuture<String> future = new CompletableFuture<>();
		s.sequence(this).await(() -> {
			log.add("started");
			return future;
		}, 10).run(() -> log.add("after")).start();
		s.tick();
		s.tick();
		assertEquals(List.of("started"), log);
		future.complete("ok");
		s.tick();
		assertEquals(List.of("started", "after"), log);
	}

	@Test
	void aFailedFutureFailsTheSequenceWithItsCause() {
		TaskScheduler s = new TaskScheduler();
		List<Throwable> failures = new ArrayList<>();
		List<String> log = new ArrayList<>();
		s.sequence(this).await(() -> CompletableFuture.failedFuture(new IllegalStateException("no chest")), 10)
			.run(() -> log.add("never")).onFail(failures::add).start();
		s.tick();
		assertTrue(log.isEmpty());
		assertEquals(1, failures.size());
		assertEquals("no chest", failures.getFirst().getMessage());
		assertFalse(s.isBusy(this));
	}

	@Test
	void awaitTimesOut() {
		TaskScheduler s = new TaskScheduler();
		List<Throwable> failures = new ArrayList<>();
		CompletableFuture<Void> never = new CompletableFuture<>();
		s.sequence(this).await(() -> never, 3).onFail(failures::add).start();
		for (int i = 0; i < 5; i++) s.tick();
		assertEquals(1, failures.size());
		assertInstanceOf(TimeoutException.class, failures.getFirst());
		assertTrue(never.isCancelled());
	}

	@Test
	void requireStopsTheSequenceWithItsMessage() {
		TaskScheduler s = new TaskScheduler();
		List<String> log = new ArrayList<>();
		s.sequence(this).run(() -> log.add("a")).require(() -> false, "No shulkers left").run(() -> log.add("b"))
			.onFail(t -> log.add(t.getMessage())).onFinish(() -> log.add("finished")).start();
		s.tick();
		assertEquals(List.of("a", "No shulkers left"), log);
	}

	@Test
	void retryStartsOverOnTheNextTickBeforeGivingUp() {
		TaskScheduler s = new TaskScheduler();
		List<String> log = new ArrayList<>();
		int[] attempts = {0};
		s.sequence(this).run(() -> {
			attempts[0]++;
			log.add("try " + attempts[0]);
		}).require(() -> attempts[0] >= 3, "not yet").run(() -> log.add("done")).retry(5).start();
		s.tick();
		s.tick();
		s.tick();
		assertEquals(List.of("try 1", "try 2", "try 3", "done"), log);
	}

	@Test
	void retryRunsOutAndReportsTheLastFailure() {
		TaskScheduler s = new TaskScheduler();
		List<String> log = new ArrayList<>();
		s.sequence(this).run(() -> log.add("try")).run(() -> {
			throw new IllegalArgumentException("broken");
		}).retry(1).onFail(t -> log.add("failed: " + t.getMessage())).start();
		for (int i = 0; i < 4; i++) s.tick();
		assertEquals(List.of("try", "try", "failed: broken"), log);
		assertFalse(s.isBusy(this));
	}

	@Test
	void cancellingCancelsTheAwaitedFuture() {
		TaskScheduler s = new TaskScheduler();
		CompletableFuture<Void> pending = new CompletableFuture<>();
		Tasks.Handle h = s.sequence(this).await(() -> pending, 20).start();
		s.tick();
		h.cancel();
		assertTrue(pending.isCancelled());
	}
}
