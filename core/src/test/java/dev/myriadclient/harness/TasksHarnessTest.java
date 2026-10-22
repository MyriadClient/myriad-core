package dev.myriadclient.harness;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.TickEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The deprecated Tasks still behave as their callers expect until they go: at the end of the tick, in order. */
@SuppressWarnings("deprecation")
class TasksHarnessTest {
	@Test
	void laterRunsAtTheEndOfItsTickAndEveryRepeatsUntilCancelled() {
		try (Harness h = Harness.start()) {
			List<String> log = new ArrayList<>();
			Myriad.events().listen(TickEvent.Pre.class, e -> log.add("pre " + h.currentTick()));
			Myriad.tasks().later("t", 2, () -> log.add("later " + h.currentTick()));
			var every = Myriad.tasks().every("t", 3, () -> log.add("every " + h.currentTick()));
			h.ticks(7);
			every.cancel();
			h.ticks(3);
			// Two ticks after it was asked for, at the end of the tick (after that tick's start), as before Jobs.
			assertTrue(log.indexOf("later 3") > log.indexOf("pre 3") && log.indexOf("later 3") < log.indexOf("pre 4"), "later out of place: " + log);
			assertEquals(List.of("every 3", "every 6"), log.stream().filter(s -> s.startsWith("every")).toList());
			assertEquals(1, log.stream().filter(s -> s.startsWith("later")).count());
		}
	}

	@Test
	void aSequenceRunsItsStepsInOrderAndWaitsBetween() {
		try (Harness h = Harness.start()) {
			List<Integer> ran = new ArrayList<>();
			boolean[] ready = {false};
			Myriad.tasks().sequence("t").run(() -> ran.add(h.currentTick())).wait(2).waitUntil(() -> ready[0], 20)
				.run(() -> ran.add(h.currentTick())).start();
			h.ticks(5);
			assertEquals(1, ran.size(), "ran past an unmet waitUntil: " + ran);
			assertTrue(Myriad.tasks().isBusy("t"));
			ready[0] = true;
			h.ticks(2);
			assertEquals(2, ran.size());
			assertFalse(Myriad.tasks().isBusy("t"));
		}
	}
}
