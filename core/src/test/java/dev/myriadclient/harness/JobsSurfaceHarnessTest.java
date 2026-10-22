package dev.myriadclient.harness;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.job.Job;
import dev.myriadclient.api.job.Progress;
import dev.myriadclient.api.job.Step;
import dev.myriadclient.api.job.Why;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.service.JobRunner;
import dev.myriadclient.impl.service.JobText;
import dev.myriadclient.impl.service.NotificationManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the player sees of jobs: the line a job reads as (the step it's on, its status, why it waits), a notice once a
 * job has been stuck five seconds (once per time it gets stuck, replacing itself), and the jobs that ended lately.
 */
class JobsSurfaceHarnessTest {
	/** A step stuck on no cobblestone while {@code stuck} holds, running otherwise; it never finishes on its own. */
	private static Step<Void> waiting(AtomicBoolean stuck) {
		return Step.of("Taking", () -> ctx -> {
			ctx.status("12 left");
			return stuck.get() ? Progress.stuck(Why.of(Why.NO_ITEM, "cobblestone")) : Progress.running();
		});
	}

	private static List<String> notices() {
		return ((NotificationManager) Myriad.notifications()).showing();
	}

	@Test
	void aJobReadsAsTheStepItsOnAndWhyItWaits() {
		try (Harness h = Harness.start()) {
			AtomicBoolean stuck = new AtomicBoolean(true);
			Job<Void> job = Myriad.jobs().start("Restocker", Step.sequence("Restock", Step.run("Open", () -> {
			}), waiting(stuck)));
			h.ticks(2);
			assertEquals("Restock · Taking · 12 left · 50% · stuck: no item: cobblestone", JobText.line(job));
			assertEquals("Restocker", JobText.owner(job));
			job.pause();
			h.tick();
			assertEquals("Restock · Taking · 12 left · 50% · paused", JobText.line(job));
			job.resume();
			stuck.set(false);
			h.tick();
			assertEquals("Restock · Taking · 12 left · 50%", JobText.line(job));
			job.cancel();
			h.tick();
			assertEquals("Restock · cancelled", JobText.line(job));
		}
	}

	@Test
	void aJobStuckFiveSecondsIsNamedOnceUntilItGetsGoingAgain() {
		try (Harness h = Harness.start()) {
			AtomicBoolean stuck = new AtomicBoolean(true);
			Myriad.jobs().start("Restocker", waiting(stuck).named("Restock"));
			h.ticks(99);
			assertTrue(notices().isEmpty(), "too soon: " + notices());
			h.ticks(3);
			assertEquals(List.of("Restocker: Restock stuck: no item: cobblestone"), notices());
			h.ticks(200);
			assertEquals(List.of("Restocker: Restock stuck: no item: cobblestone"), notices(), "once, not every tick");
			stuck.set(false);
			h.ticks(5);
			stuck.set(true);
			h.ticks(102);
			// Stuck again: the same notice comes back in the same place, counted.
			assertEquals(List.of("Restocker: Restock stuck: no item: cobblestone ×2"), notices());
		}
	}

	@Test
	void outOfReachIsntNoticed() {
		try (Harness h = Harness.start()) {
			Myriad.jobs().start("Printer", Step.of("Printing", () -> ctx -> Progress.stuck(Why.of(Why.OUT_OF_REACH, "×5"))));
			h.ticks(150);
			assertTrue(notices().isEmpty(), notices().toString());
		}
	}

	@Test
	void aPausedJobIsntStuck() {
		try (Harness h = Harness.start()) {
			Job<Void> job = Myriad.jobs().start("Restocker", waiting(new AtomicBoolean(true)).named("Restock"));
			h.tick();
			job.pause();
			h.ticks(150);
			assertTrue(notices().isEmpty(), notices().toString());
		}
	}

	@Test
	void jobsThatEndedAreKeptAWhile() {
		try (Harness h = Harness.start()) {
			Job<Void> job = Myriad.jobs().start("Restocker", Step.run("Done", () -> {
			}));
			h.ticks(2);
			List<JobRunner.Ended> ended = MyriadImpl.get().jobRunner().ended();
			assertEquals(1, ended.size());
			assertEquals(job, ended.getFirst().job());
			h.pass(JobRunner.ENDED_MS + 1);
			assertTrue(MyriadImpl.get().jobRunner().ended().isEmpty());
		}
	}
}
