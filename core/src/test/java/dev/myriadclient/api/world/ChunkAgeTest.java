package dev.myriadclient.api.world;

import org.junit.jupiter.api.Test;

import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The palette-order verdict on sections made up of state ids (0 is air here). */
class ChunkAgeTest {
	private static final int AIR = 0, STONE = 1, DIRT = 2, WATER = 3;

	private static int verdict(int[] palette, IntUnaryOperator entryAt) {
		return ChunkAge.verdict(palette, entryAt, id -> id == AIR);
	}

	@Test
	void generatedUndergroundHasAirFirstInThePaletteButStoneAtTheFirstPosition() {
		// The generator started from air (entry 0), then placed stone (1) everywhere.
		assertEquals(1, verdict(new int[]{AIR, STONE}, i -> 1));
	}

	@Test
	void savedSectionHasTheFirstPositionsBlockAsEntryZero() {
		// Rebuilt on saving: stone at the first position is entry 0.
		assertEquals(-1, verdict(new int[]{STONE, AIR}, i -> i < 2048 ? 0 : 1));
	}

	@Test
	void airAtTheFirstPositionOnlyTellsWhenTheNextBlockIsNotEntryOne() {
		// Saved in open air: air, then the next block met is entry 1. Can't tell (a generated one looks the same).
		assertEquals(0, verdict(new int[]{AIR, DIRT, WATER}, i -> i < 100 ? 0 : 1));
		// Generated: water was placed before dirt, but dirt comes first by position.
		assertEquals(1, verdict(new int[]{AIR, WATER, DIRT}, i -> i < 100 ? 0 : 2));
		// Only air at every position: no vote.
		assertEquals(0, verdict(new int[]{AIR, STONE}, i -> 0));
	}
}
