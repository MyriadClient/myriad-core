package dev.myriadclient.impl.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PresentationTest {
	/** An inventory: what each slot holds now; swaps change it as the game would. */
	private String[] inv;
	private String[] before;
	private Presentation shown;

	@BeforeEach
	void setUp() {
		inv = new String[41];
		Arrays.fill(inv, "");
		inv[0] = "sword";
		inv[1] = "pick";
		inv[3] = "food";
		inv[20] = "rocket";
		inv[21] = "cobble";
		inv[22] = "glass";
		before = inv.clone();
		shown = new Presentation();
	}

	private void swap(Object owner, int a, int b) {
		String t = inv[a];
		inv[a] = inv[b];
		inv[b] = t;
		shown.swapped(owner, a, b);
	}

	private String showing(int index) {
		return inv[shown.source(index)];
	}

	/** Every slot shows what it held before the swaps (counts aside). */
	private void looksAsBefore() {
		for (int i = 0; i < inv.length; i++) assertEquals(before[i], showing(i), "slot " + i);
	}

	@Test
	void borrowShowsAsBeforeThenGoesAway() {
		Object mod = new Object();
		swap(mod, 20, 3);
		assertEquals("rocket", inv[3]);
		looksAsBefore();
		swap(mod, 20, 3);
		assertTrue(shown.isEmpty(), "back where it started: nothing kept");
		looksAsBefore();
	}

	@Test
	void buildChainsMoves() {
		// A build brings cobble into slot 1 (the pick goes to 21), then glass into 1 (cobble goes to 22), then puts it back.
		Object build = new Object();
		swap(build, 21, 1);
		swap(build, 22, 1);
		looksAsBefore();
		assertEquals("glass", inv[1]);
		swap(build, 22, 1);
		swap(build, 21, 1);
		assertTrue(shown.isEmpty());
		looksAsBefore();
	}

	@Test
	void twoOwnersApart() {
		Object a = new Object(), b = new Object();
		swap(a, 20, 3);
		swap(b, 21, 1);
		looksAsBefore();
		swap(a, 20, 3);
		looksAsBefore();
		assertFalse(shown.isEmpty(), "the other's still out");
		swap(b, 21, 1);
		assertTrue(shown.isEmpty());
	}

	@Test
	void forgottenShowsAsItIs() {
		Object mod = new Object();
		swap(mod, 20, 3);
		shown.forget(mod);
		assertEquals("rocket", showing(3));
	}

	@Test
	void usedUpShowsWhatsLeft() {
		// Placing from a borrowed stack: the slot it came from shows the stack as it is now (fewer, or gone).
		Object mod = new Object();
		swap(mod, 21, 3);
		inv[3] = "";
		assertEquals("food", showing(3));
		assertEquals("", showing(21));
	}
}
