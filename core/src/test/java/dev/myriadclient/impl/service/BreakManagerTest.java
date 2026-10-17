package dev.myriadclient.impl.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BreakManagerTest {
	@Test
	void serverTicksMatchTheServersFormula() {
		// Stone with a diamond pickaxe: 8 / 1.5 / 30 per tick.
		float stone = 8f / 1.5f / 30f;
		// Vanilla's bar fills after 6 ticks: 6 * 0.178 >= 1.
		assertEquals(5, BreakManager.serverTicksToReach(stone, 1f)); // server: rate * (5 + 1) >= 1
		// The server accepts a finish at 0.7: rate * (3 + 1) = 0.71.
		assertEquals(3, BreakManager.serverTicksToReach(stone, BreakManager.PACKET_THRESHOLD));
		// Instant blocks need no waiting, and unbreakable ones never finish.
		assertEquals(0, BreakManager.serverTicksToReach(1.5f, 0.7f));
		assertEquals(Integer.MAX_VALUE, BreakManager.serverTicksToReach(0, 1f));
		// Exact multiples don't round up a tick.
		assertEquals(9, BreakManager.serverTicksToReach(0.1f, 1f));
	}
}
