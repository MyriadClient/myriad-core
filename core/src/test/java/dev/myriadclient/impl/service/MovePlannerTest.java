package dev.myriadclient.impl.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MovePlannerTest {
	/** Plays a plan back with the game's click rules and returns {cursor, source, destination}. */
	private static int[] play(MovePlanner.Step[] steps, int c, int s, int d, int sMax, int dMax, int stackMax) {
		for (MovePlanner.Step step : steps) {
			int slot = step.onDestination() ? d : s, max = step.onDestination() ? dMax : sMax;
			if (c == 0) {
				int taken = Math.min(stackMax, step.button() == 0 ? slot : (slot + 1) / 2);
				c = taken;
				slot -= taken;
			} else {
				int put = step.button() == 0 ? Math.min(c, max - slot) : (slot < max ? 1 : 0);
				c -= put;
				slot += put;
			}
			if (step.onDestination()) d = slot;
			else s = slot;
		}
		return new int[]{c, s, d};
	}

	@Test
	void wholeStackIsTwoClicks() {
		MovePlanner.Step[] p = MovePlanner.plan(0, 64, 0, 64, 64, 64, 64);
		assertNotNull(p);
		assertEquals(2, p.length);
	}

	@Test
	void everyCountReachesItsGoal() {
		int worst = 0;
		for (int source = 1; source <= 64; source++) {
			for (int goal = 1; goal <= source; goal++) {
				MovePlanner.Step[] p = MovePlanner.plan(0, source, 0, goal, 64, 64, 64);
				assertNotNull(p, source + " -> " + goal);
				int[] end = play(p, 0, source, 0, 64, 64, 64);
				assertEquals(0, end[0]);
				assertEquals(goal, end[2]);
				assertEquals(source - goal, end[1]);
				worst = Math.max(worst, p.length);
			}
		}
		System.out.println("worst plan: " + worst + " clicks");
		// Well short of moving items one at a time.
		assertEquals(true, worst <= 16, "worst plan " + worst + " clicks");
	}

	@Test
	void topsUpAndTrims() {
		int[] end = play(MovePlanner.plan(0, 30, 50, 64, 64, 64, 64), 0, 30, 50, 64, 64, 64);
		assertEquals(64, end[2]);
		end = play(MovePlanner.plan(0, 10, 50, 37, 64, 64, 64), 0, 10, 50, 64, 64, 64);
		assertEquals(37, end[2]);
		assertEquals(23, end[1]);
	}

	@Test
	void startsWithItemsOnTheCursor() {
		MovePlanner.Step[] p = MovePlanner.plan(64, 0, 0, 40, 64, 64, 64);
		int[] end = play(p, 64, 0, 0, 64, 64, 64);
		assertEquals(0, end[0]);
		assertEquals(40, end[2]);
	}

	@Test
	void sixteenStacks() {
		int[] end = play(MovePlanner.plan(0, 16, 0, 5, 16, 16, 16), 0, 16, 0, 16, 16, 16);
		assertEquals(5, end[2]);
	}

	@Test
	void impossible() {
		assertNull(MovePlanner.plan(0, 10, 0, 11, 64, 64, 64));
		assertNull(MovePlanner.plan(0, 64, 64, 0, 64, 64, 64));
	}
}
