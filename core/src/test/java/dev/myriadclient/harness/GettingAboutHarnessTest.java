package dev.myriadclient.harness;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.elytra.Flight;
import dev.myriadclient.api.job.Job;
import dev.myriadclient.api.job.MaterialSource;
import dev.myriadclient.api.job.Step;
import dev.myriadclient.api.job.Steps;
import dev.myriadclient.api.job.Why;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code Steps.acquire} over its sources, {@code Steps.fly} over a flight, and {@code Steps.walkTo} without Baritone. */
class GettingAboutHarnessTest {
	private static boolean obsidian(ItemStack s) {
		return s.is(Items.OBSIDIAN);
	}

	/** A source that puts {@code n} obsidian in inventory slot {@code index} and says so. */
	private static MaterialSource giving(Harness h, int index, int n, AtomicInteger asked) {
		return (what, count) -> Step.supply("Give", () -> {
			asked.incrementAndGet();
			h.give(index, new ItemStack(Items.OBSIDIAN, n));
			return n;
		});
	}

	@Test
	void acquireIsDoneAtOnceWithEnoughAndTriesSourcesInTurnOtherwise() {
		try (Harness h = Harness.start()) {
			h.standAt(0.5, 64, 0.5, 0, 0).give(0, new ItemStack(Items.OBSIDIAN, 20));
			AtomicInteger first = new AtomicInteger(), second = new AtomicInteger(), third = new AtomicInteger();
			Job<Integer> enough = Myriad.jobs().start("Builder", Steps.acquire(GettingAboutHarnessTest::obsidian, 16, List.of(giving(h, 1, 64, first))));
			h.tick();
			assertEquals(Job.State.DONE, enough.state());
			assertEquals(20, enough.result().join());
			assertEquals(0, first.get(), "nothing fetched when there's enough");

			MaterialSource broken = (what, count) -> Step.fail(Why.of(Why.NO_CONTAINER, "gone"));
			Job<Integer> job = Myriad.jobs().start("Builder", Steps.acquire(GettingAboutHarnessTest::obsidian, 50,
				List.of(broken, giving(h, 1, 10, first), giving(h, 2, 30, second), giving(h, 3, 64, third))));
			h.ticks(10);
			assertEquals(Job.State.DONE, job.state(), "why: " + job.why());
			assertEquals(60, job.result().join());
			assertEquals(List.of(1, 1, 0), List.of(first.get(), second.get(), third.get()), "a broken source passed over, stopping once there's enough");
		}
	}

	@Test
	void acquireIsStuckShortOnceEverySourceWasTried() {
		try (Harness h = Harness.start()) {
			h.standAt(0.5, 64, 0.5, 0, 0);
			AtomicInteger asked = new AtomicInteger();
			Job<Integer> job = Myriad.jobs().start("Builder", Steps.acquire(GettingAboutHarnessTest::obsidian, 64, List.of(giving(h, 1, 12, asked))));
			h.ticks(5);
			assertEquals(Job.State.STUCK, job.state());
			assertTrue(job.why().is(Why.NO_ITEM), job.why().toString());
			assertEquals("×52 short", job.why().detail());
		}
	}

	@Test
	void flyLandsWhenToldAndIsDoneOnceDown() {
		try (Harness h = Harness.start()) {
			FakeFlight flight = new FakeFlight();
			AtomicInteger legs = new AtomicInteger();
			Job<Void> job = Myriad.jobs().start("Search", Steps.fly(flight, f -> {
				f.heading(legs.get() * 90);
				return legs.incrementAndGet() < 4;
			}));
			h.ticks(2);
			assertTrue(flight.started);
			assertTrue(job.state().isActive(), "down before its first tick isn't landed");
			h.ticks(6);
			assertEquals(List.of("start", "heading 0.0", "heading 90.0", "heading 180.0", "heading 270.0", "land"), flight.calls.subList(0, 6));
			flight.down(null);
			assertEquals("stop", flight.calls.getLast(), "stopped as it lands, before it can take off again");
			h.tick();
			assertEquals(Job.State.DONE, job.state(), "why: " + job.why());
		}
	}

	@Test
	void flyIsStuckWhileTheFlightIsAndFailsWhenItHadToComeDown() {
		try (Harness h = Harness.start()) {
			FakeFlight flight = new FakeFlight();
			Job<Void> job = Myriad.jobs().start("Search", Steps.fly(flight, f -> true));
			h.ticks(2);
			flight.state = Flight.State.STUCK;
			flight.why = Why.of(Why.NO_ELYTRA, "");
			h.tick();
			assertEquals(Job.State.STUCK, job.state());
			assertTrue(job.why().is(Why.NO_ELYTRA));
			flight.down(Why.of(Why.NO_ELYTRA, "the last elytra is worn out"));
			h.tick();
			assertEquals(Job.State.FAILED, job.state());
			assertTrue(job.why().is(Why.NO_ELYTRA));
		}
	}

	@Test
	void aFlightFlownAgainIsHeardOnce() {
		try (Harness h = Harness.start()) {
			FakeFlight flight = new FakeFlight();
			for (int i = 0; i < 3; i++) {
				Job<Void> job = Myriad.jobs().start("Search", Steps.fly(flight, f -> false));
				h.ticks(2);
				flight.down(null);
				h.tick();
				assertEquals(Job.State.DONE, job.state());
			}
			assertEquals(1, flight.landedListeners.size());
		}
	}

	@Test
	void cancellingAFlightStopsIt() {
		try (Harness h = Harness.start()) {
			FakeFlight flight = new FakeFlight();
			Job<Void> job = Myriad.jobs().start("Search", Steps.fly(flight, f -> true));
			h.ticks(3);
			job.cancel();
			assertEquals("stop", flight.calls.getLast());
			assertFalse(flight.calls.contains("land"));
		}
	}

	@Test
	void walkingIsDoneWhereYouAreAndFailsWithoutBaritone() {
		try (Harness h = Harness.start()) {
			h.standAt(0.5, 64, 0.5, 0, 0);
			Job<Void> here = Myriad.jobs().start("Recast", Steps.walkNear(new BlockPos(2, 64, 0), 3));
			Job<Void> far = Myriad.jobs().start("Recast", Steps.walkTo(new BlockPos(40, 64, 0)));
			h.tick();
			assertEquals(Job.State.DONE, here.state());
			assertEquals(Job.State.FAILED, far.state());
			assertTrue(far.why().is(Why.NO_PATHING), far.why().toString());
		}
	}

	/** A flight that records what it's asked and is in whatever state the test puts it. */
	private static final class FakeFlight implements Flight {
		final List<String> calls = new ArrayList<>();
		final List<Consumer<@Nullable Why>> landedListeners = new ArrayList<>();
		State state = State.DOWN;
		@Nullable Why why;
		boolean started;

		/** Down, telling its listeners as it lands, and then (as a real one left running does in water) up again. */
		void down(@Nullable Why why) {
			state = State.DOWN;
			this.why = why;
			for (Consumer<@Nullable Why> l : List.copyOf(landedListeners)) l.accept(why);
			if (!calls.getLast().equals("stop")) state = State.CRUISING;
		}

		@Override
		public Flight heading(float yaw) {
			calls.add("heading " + yaw);
			return this;
		}

		@Override
		public Flight speed(double blocksPerSecond) {
			return this;
		}

		@Override
		public Flight height(double y) {
			return this;
		}

		@Override
		public Flight climb(double blocksPerSecond) {
			return this;
		}

		@Override
		public Flight preserve(double percent) {
			return this;
		}

		@Override
		public Flight onLanded(Consumer<@Nullable Why> landed) {
			landedListeners.add(landed);
			return this;
		}

		@Override
		public void start() {
			calls.add("start");
			started = true;
			// Up from its first tick, as the real one is.
			Myriad.jobs().later("flight", 0, () -> {
				if (state == State.DOWN) state = State.CRUISING;
			});
		}

		@Override
		public void stop() {
			calls.add("stop");
		}

		@Override
		public void land() {
			calls.add("land");
			state = State.LANDING;
		}

		@Override
		public State state() {
			return state;
		}

		@Override
		public @Nullable Why why() {
			return why;
		}

		@Override
		public @Nullable String status() {
			return null;
		}
	}
}
