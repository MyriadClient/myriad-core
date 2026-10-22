package dev.myriadclient.api.job;

import dev.myriadclient.api.event.Subscription;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The runner's contract (roadmap 4.4) on the engine alone: no game, a host that records what it's asked. */
class JobEngineTest {
	private final List<String> log = new ArrayList<>();
	private final List<Job<?>> released = new ArrayList<>();
	private final List<Object> events = new ArrayList<>();
	private final List<Why> notices = new ArrayList<>();
	private final List<Consumer<Object>> listeners = new ArrayList<>();
	private final JobEngine engine = new JobEngine(new JobEngine.Host() {
		@Override
		public void release(Job<?> job) {
			released.add(job);
		}

		@Override
		@SuppressWarnings("unchecked")
		public <E> Subscription listen(Class<E> type, Consumer<? super E> handler) {
			Consumer<Object> l = o -> {
				if (type.isInstance(o)) ((Consumer<Object>) handler).accept(o);
			};
			listeners.add(l);
			return () -> listeners.remove(l);
		}

		@Override
		public void post(Object event) {
			events.add(event);
		}

		@Override
		public void failed(Job<?> root, Why why) {
			notices.add(why);
		}

		@Override
		public void log(Job<?> job, String message) {
		}
	});
	private final Object owner = "owner";

	private Step<Void> mark(String s) {
		return Step.run(s, () -> log.add(s));
	}

	private void ticks(int n) {
		for (int i = 0; i < n; i++) engine.tick();
	}

	/** A step stuck on {@code code} until {@code clear} is set. */
	private static Step<Void> stuckUntil(Why.Code code, AtomicBoolean clear) {
		return Step.of("Stuck", () -> ctx -> clear.get() ? Progress.done() : Progress.stuck(Why.of(code, "")));
	}

	/** A step that runs forever and notes its lifecycle calls. */
	private Step<Void> forever(String name) {
		return Step.of(name, () -> new Step.Run<>() {
			@Override
			public void start(JobContext ctx) {
				log.add(name + " start");
			}

			@Override
			public Progress<Void> tick(JobContext ctx) {
				log.add(name + " tick");
				return Progress.running();
			}

			@Override
			public void pause(JobContext ctx) {
				log.add(name + " pause");
			}

			@Override
			public void resume(JobContext ctx) {
				log.add(name + " resume");
			}

			@Override
			public void cancel(JobContext ctx) {
				log.add(name + " cancel");
			}
		});
	}

	@Test
	void instantStepsHandOverInOneTick() {
		Job<Void> job = engine.start(owner, Step.sequence("Seq", mark("a"), mark("b"), Step.delay(2), mark("c")));
		assertEquals(Job.State.QUEUED, job.state());
		engine.tick();
		assertEquals(List.of("a", "b"), log);
		engine.tick();
		assertEquals(List.of("a", "b"), log);
		engine.tick();
		assertEquals(List.of("a", "b", "c"), log);
		assertEquals(Job.State.DONE, job.state());
		assertTrue(job.result().isDone());
	}

	@Test
	void resultsPassThroughThen() {
		Job<String> job = engine.start(owner, Step.supply("Two", () -> 2).then(n -> Step.supply("Times", () -> n * 21)).then(n -> Step.supply("Say", () -> "got " + n)));
		engine.tick();
		assertEquals("got 42", job.result().join());
		assertEquals("Two\n  Two\n  (the next step, from the result)\n  (the next step, from the result)",
			Step.supply("Two", () -> 2).then(n -> Step.supply("Times", () -> n)).then(n -> Step.nothing()).toString());
	}

	@Test
	void allWaitsForEveryOneAndAFailureCancelsTheRest() {
		Job<List<Integer>> ok = engine.start(owner, Step.all("Both", List.of(Step.supply("1", () -> 1), Step.delay(1).then(Step.supply("2", () -> 2)))));
		ticks(2);
		assertEquals(List.of(1, 2), ok.result().join());

		Job<List<Void>> bad = engine.start(owner, Step.all("Bad", List.of(forever("f"), Step.fail(Why.of(Why.NO_ITEM, "dirt")))));
		engine.tick();
		assertEquals(Job.State.FAILED, bad.state());
		assertTrue(bad.why().is(Why.NO_ITEM));
		assertTrue(log.contains("f cancel"));
	}

	@Test
	void theFirstToFinishWinsARace() {
		Job<String> job = engine.start(owner, Step.race("Race", List.of(forever("slow").then(Step.supply("s", () -> "slow")), Step.delay(1).then(Step.supply("f", () -> "fast")))));
		ticks(2);
		assertEquals("fast", job.result().join());
		assertTrue(log.contains("slow cancel"));
	}

	@Test
	void aRemedyRunsInPlaceOfTheStuckStepThenItCarriesOn() {
		AtomicBoolean cobble = new AtomicBoolean();
		AtomicInteger fetches = new AtomicInteger();
		Job<Void> job = engine.start(owner, stuckUntil(Why.NO_ITEM, cobble)
			.whenStuck(Why.NO_ITEM, 2, why -> Step.run("Fetch", () -> {
				fetches.incrementAndGet();
				cobble.set(true);
			}).then(Step.delay(1))));
		ticks(2);
		assertEquals(Job.State.STUCK, job.state());
		assertTrue(job.why().is(Why.NO_ITEM));
		engine.tick();
		assertEquals(1, fetches.get());
		assertEquals(1, job.children().size());
		assertEquals("Fetch", job.children().getFirst().name());
		ticks(2);
		assertEquals(Job.State.DONE, job.state());
		assertEquals(1, fetches.get());
	}

	@Test
	void aRemedyThatFailsFailsTheStep() {
		Job<Void> job = engine.start(owner, stuckUntil(Why.NO_ROOM, new AtomicBoolean()).whenStuck(Why.NO_ROOM, 0, Step::fail));
		ticks(2);
		assertEquals(Job.State.FAILED, job.state());
		assertTrue(job.why().is(Why.NO_ROOM));
	}

	@Test
	void stuckForAndTimeoutGiveUp() {
		Job<Void> stuck = engine.start(owner, stuckUntil(Why.UNLOADED, new AtomicBoolean()).stuckFor(3));
		Job<Void> slow = engine.start(owner, forever("slow").timeout(5));
		ticks(4);
		assertEquals(Job.State.FAILED, stuck.state());
		assertTrue(stuck.why().is(Why.UNLOADED));
		assertEquals(Job.State.RUNNING, slow.state());
		ticks(2);
		assertEquals(Job.State.FAILED, slow.state());
		assertTrue(slow.why().is(Why.TIMEOUT));
		assertTrue(log.contains("slow cancel"));
	}

	@Test
	void retryStartsOverInTheSameJobAfterItsFinally() {
		AtomicInteger tries = new AtomicInteger();
		Job<String> job = engine.start(owner, Step.supply("Try", () -> {
				if (tries.incrementAndGet() < 3) throw Why.of(Why.SERVER_REFUSED, "no").asException();
				return "ok";
			}).finallyDo(() -> log.add("finally " + tries.get())).retry(5));
		ticks(5);
		assertEquals("ok", job.result().join());
		assertEquals(List.of("finally 1", "finally 2", "finally 3"), log);
		assertEquals(List.of("retry 2/5"), job.pills());
	}

	@Test
	void retryGivesUpAndRecoverTakesOver() {
		Job<String> job = engine.start(owner, Step.<String>fail(Why.of(Why.BLOCKED, "lane")).retry(1).recover(why -> Step.supply("Other way", () -> "around " + why.detail())));
		ticks(3);
		assertEquals("around lane", job.result().join());
		assertEquals("Other way", job.name());
	}

	@Test
	void cancellingCascadesAndEveryJobLetsGo() {
		Job<Void> job = engine.start(owner, Step.sequence("Outer", Step.all("Both", List.of(forever("x"), forever("y")))));
		engine.tick();
		int jobs = released.size();
		assertEquals(0, jobs);
		job.cancel();
		assertTrue(log.containsAll(List.of("x cancel", "y cancel")));
		assertEquals(4, released.size());
		assertEquals(job, released.getLast());
		assertEquals(Job.State.CANCELLED, job.state());
		assertTrue(job.result().isCompletedExceptionally());
		assertTrue(notices.isEmpty());
	}

	@Test
	void pausingStopsTheTicksAndTellsTheSteps() {
		Job<Void> job = engine.start(owner, Step.sequence("Seq", forever("w")));
		engine.tick();
		log.clear();
		job.pause();
		assertEquals(Job.State.PAUSED, job.state());
		ticks(3);
		assertEquals(List.of("w pause"), log);
		job.resume();
		engine.tick();
		assertEquals(List.of("w pause", "w resume", "w tick"), log);
		assertEquals(2, job.ticks());
	}

	@Test
	void theOwnerTurningOffOrLeavingTheWorldEndsJobs() {
		Module m = new Module(Tags.WORLD, "Printer", "") {
		};
		Job<Void> mine = engine.start(m, forever("p"));
		Job<Void> other = engine.start(owner, forever("o"));
		engine.tick();
		engine.cancel(m);
		assertTrue(mine.why().is(Why.OWNER_OFF));
		assertEquals(Job.State.RUNNING, other.state());
		engine.cancelAll(Why.of(Why.LEFT_WORLD, ""));
		assertTrue(other.why().is(Why.LEFT_WORLD));
		assertTrue(engine.running().isEmpty());
	}

	@Test
	void contextEventsArriveOnTheStepsTickAndStopWithIt() {
		List<String> heard = new ArrayList<>();
		AtomicBoolean finish = new AtomicBoolean();
		engine.start(owner, Step.of("Listen", () -> new Step.Run<Void>() {
			@Override
			public void start(JobContext ctx) {
				ctx.on(String.class, heard::add);
			}

			@Override
			public Progress<Void> tick(JobContext ctx) {
				return finish.get() ? Progress.done() : Progress.running();
			}
		}));
		engine.tick();
		fire("one");
		assertTrue(heard.isEmpty());
		engine.tick();
		assertEquals(List.of("one"), heard);
		finish.set(true);
		engine.tick();
		assertTrue(listeners.isEmpty());
		fire("two");
		assertEquals(List.of("one"), heard);
	}

	@Test
	void anUnhandledRootFailureIsReportedOnce() {
		engine.start(owner, Step.fail(Why.of(Why.NO_CONTAINER, "chest")));
		engine.start(owner, Step.fail(Why.of(Why.NO_CONTAINER, "handled")).onFailed(why -> log.add("handled")));
		Job<Void> watched = engine.start(owner, Step.fail(Why.of(Why.NO_CONTAINER, "watched")));
		CompletableFuture<Void> f = watched.result().exceptionally(t -> {
			log.add("watched " + Why.of(t).detail());
			return null;
		});
		engine.tick();
		assertEquals(1, notices.size());
		assertEquals("no container: chest", notices.getFirst().toString());
		assertTrue(f.isDone());
		assertEquals(List.of("handled", "watched watched"), log);
	}

	@Test
	void theTreeShowsIntentNotPlumbing() {
		Step<Void> s = Step.sequence("Build kit", mark("Gather").retry(2).timeout(400), Step.when(() -> true, mark("Deliver")))
			.whenStuck(Why.NO_ITEM, 20, why -> Step.nothing());
		assertEquals("Build kit  [when stuck: no item]\n  Gather  [retry 2, timeout 400]\n  Deliver  [if]", s.toString());
		assertEquals("Build kit", s.name());
	}

	@Test
	void laterAndEveryRunOnTheJobTick() {
		AtomicInteger runs = new AtomicInteger();
		engine.later(owner, 2, runs::incrementAndGet);
		Job<Void> every = engine.every(owner, 3, () -> log.add("e"));
		ticks(2);
		assertEquals(0, runs.get());
		engine.tick();
		assertEquals(1, runs.get());
		ticks(4);
		assertEquals(List.of("e", "e", "e"), log);
		every.cancel();
		ticks(5);
		assertEquals(3, log.size());
	}

	@Test
	void eventsTellWatchersWhatHappened() {
		Job<Void> job = engine.start(owner, Step.sequence("Seq", Step.delay(1)));
		engine.tick();
		engine.tick();
		assertSame(job, ((JobEvent) events.getFirst()).job());
		assertTrue(events.getFirst() instanceof JobEvent.Started);
		assertTrue(events.stream().anyMatch(e -> e instanceof JobEvent.Changed c && c.job() == job && c.before() == Job.State.QUEUED));
		assertTrue(events.getLast() instanceof JobEvent.Finished f && f.job() == job);
		assertFalse(engine.isBusy(owner));
	}

	private void fire(Object event) {
		for (Consumer<Object> l : List.copyOf(listeners)) l.accept(event);
	}
}
