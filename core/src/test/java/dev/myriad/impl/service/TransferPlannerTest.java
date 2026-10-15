package dev.myriad.impl.service;

import net.minecraft.world.inventory.ContainerInput;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TransferPlannerTest {
	/** A menu with vanilla's click rules: kind 1 stacks to 64, kind 2 to 16; slot 0.. are the container's. */
	static final class Sim implements TransferPlanner.Grid {
		final int[] kind, count;
		final int[] shiftTo;
		int cursorKind, cursorCount, clicks;
		int wantedKind = 1;

		Sim(int slots, int[] shiftTo) {
			kind = new int[slots];
			count = new int[slots];
			this.shiftTo = shiftTo;
		}

		Sim put(int slot, int k, int n) {
			kind[slot] = k;
			count[slot] = n;
			return this;
		}

		static int max(int k) {
			return k == 2 ? 16 : 64;
		}

		@Override public int kind(int slot) { return count[slot] == 0 ? 0 : kind[slot]; }
		@Override public int count(int slot) { return count[slot]; }
		@Override public int capacity(int slot, int k) { return max(k); }
		@Override public boolean wanted(int slot) { return count[slot] > 0 && kind[slot] == wantedKind; }
		@Override public boolean mayPickup(int slot) { return true; }
		@Override public int cursorKind() { return cursorCount == 0 ? 0 : cursorKind; }
		@Override public int cursorCount() { return cursorCount; }

		void apply(TransferPlanner.Click c) {
			clicks++;
			int s = c.slot();
			if (c.input() == ContainerInput.QUICK_MOVE) {
				int k = kind[s];
				for (int pass = 0; pass < 2 && count[s] > 0; pass++) {
					for (int t : shiftTo) {
						boolean fits = pass == 0 ? count[t] > 0 && kind[t] == k : count[t] == 0;
						if (!fits) continue;
						int moved = Math.min(count[s], max(k) - count[t]);
						kind[t] = k;
						count[t] += moved;
						count[s] -= moved;
						if (count[s] == 0) break;
					}
				}
				return;
			}
			boolean left = c.button() == 0;
			if (cursorCount == 0) {
				int take = left ? count[s] : (count[s] + 1) / 2;
				cursorKind = kind[s];
				cursorCount = take;
				count[s] -= take;
			} else if (count[s] == 0 || kind[s] == cursorKind) {
				int moved = Math.min(left ? cursorCount : 1, max(cursorKind) - count[s]);
				kind[s] = cursorKind;
				count[s] += moved;
				cursorCount -= moved;
			} else {
				int k = kind[s], n = count[s];
				kind[s] = cursorKind;
				count[s] = cursorCount;
				cursorKind = k;
				cursorCount = n;
			}
		}

		int total(int[] slots, int k) {
			int n = 0;
			for (int s : slots) if (kind[s] == k) n += count[s];
			return n;
		}
	}

	static final int[] CHEST = IntStream.range(0, 27).toArray();
	static final int[] PLAYER = IntStream.range(27, 63).toArray();

	/** Runs the plan to the end (or a click cap), recounting what's left to move before each new stack, as the transfer does. */
	static void run(Sim sim, TransferPlanner plan, int[] source, int wanted) {
		int baseline = sim.total(source, sim.wantedKind);
		for (int i = 0; i < 1000; i++) {
			int remaining = wanted - (baseline - sim.total(source, sim.wantedKind) - (sim.cursorKind() == sim.wantedKind ? sim.cursorCount : 0));
			TransferPlanner.Click c = plan.next(sim, remaining);
			if (c == null) return;
			sim.apply(c);
		}
		fail("the plan never ended");
	}

	@Test
	void wholeStacksGoWithOneShiftClickEach() {
		Sim sim = new Sim(63, PLAYER).put(0, 1, 64).put(3, 1, 30).put(5, 1, 64).put(6, 2, 16);
		run(sim, new TransferPlanner(CHEST, PLAYER, true), CHEST, Integer.MAX_VALUE);
		assertEquals(158, sim.total(PLAYER, 1));
		assertEquals(16, sim.total(CHEST, 2), "other items stay");
		assertEquals(3, sim.clicks);
		assertEquals(0, sim.cursorCount);
	}

	@Test
	void anExactCountSplitsTheLastStack() {
		Sim sim = new Sim(63, PLAYER).put(0, 1, 64).put(1, 1, 64);
		run(sim, new TransferPlanner(CHEST, PLAYER, true), CHEST, 100);
		assertEquals(100, sim.total(PLAYER, 1));
		assertEquals(28, sim.total(CHEST, 1));
		assertEquals(0, sim.cursorCount);
		// One shift click, then a pick-up, 28 single items back (fewer than the 36 going on), and the rest down.
		assertEquals(1 + 1 + 28 + 1, sim.clicks);
	}

	@Test
	void aSmallShareGoesOneByOne() {
		Sim sim = new Sim(63, PLAYER).put(0, 1, 64);
		run(sim, new TransferPlanner(CHEST, PLAYER, true), CHEST, 3);
		assertEquals(3, sim.total(PLAYER, 1));
		assertEquals(61, sim.total(CHEST, 1));
		assertEquals(1 + 3 + 1, sim.clicks);
	}

	@Test
	void onlyWhatFitsMoves() {
		Sim sim = new Sim(63, PLAYER).put(0, 1, 64).put(1, 1, 64);
		// The player's inventory is full but for 10 diamonds' room on a stack of 54.
		for (int s : PLAYER) sim.put(s, 2, 16);
		sim.put(40, 1, 54);
		run(sim, new TransferPlanner(CHEST, PLAYER, true), CHEST, Integer.MAX_VALUE);
		assertEquals(64, sim.count[40]);
		assertEquals(118, sim.total(CHEST, 1));
		assertEquals(0, sim.cursorCount);
	}

	@Test
	void withoutShiftClicksItemsLandOnlyWhereAsked() {
		// The hotbar (the last 9 player slots) is left out of where items may go.
		int[] main = IntStream.range(27, 54).toArray();
		Sim sim = new Sim(63, PLAYER).put(0, 1, 64).put(1, 1, 20).put(29, 1, 50);
		run(sim, new TransferPlanner(CHEST, main, false), CHEST, Integer.MAX_VALUE);
		assertEquals(134, sim.total(main, 1));
		assertEquals(0, sim.total(IntStream.range(54, 63).toArray(), 1));
		assertEquals(0, sim.cursorCount);
		// The partial stack is topped up first.
		assertEquals(64, sim.count[29]);
	}

	@Test
	void puttingFillsPartialStacksInTheContainer() {
		Sim sim = new Sim(63, CHEST).put(0, 1, 60).put(27, 1, 64).put(28, 1, 10);
		run(sim, new TransferPlanner(PLAYER, CHEST, true), PLAYER, Integer.MAX_VALUE);
		assertEquals(0, sim.total(PLAYER, 1));
		assertEquals(134, sim.total(CHEST, 1));
	}

	@Test
	void aCursorItDidNotPickUpStopsThePlan() {
		Sim sim = new Sim(63, PLAYER).put(0, 1, 64);
		sim.cursorKind = 2;
		sim.cursorCount = 5;
		assertNull(new TransferPlanner(CHEST, PLAYER, true).next(sim, 10));
	}
}
