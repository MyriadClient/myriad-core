package dev.myriadclient.api.util;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FuzzyMatchTest {
	private static boolean m(String text, String q) {
		return FuzzyMatch.matches(text, q);
	}

	@Test
	void matchesTheWaysPeopleType() {
		assertTrue(m("Kill Aura", "kill"));
		assertTrue(m("Kill Aura", "aura"));
		assertTrue(m("Kill Aura", "killaura"));
		assertTrue(m("Kill Aura", "kill aura"));
		assertTrue(m("Kill Aura", "ka"));
		assertTrue(m("Kill Aura", "kaur"));
		assertTrue(m("Auto Reconnect", "ar"));
		assertTrue(m("Auto Reconnect", "reco"));
		assertTrue(m("No Fall", "nofall"));
		assertTrue(m("ESP", "esp"));
	}

	@Test
	void rejectsScatteredLetters() {
		assertFalse(m("Auto Totem", "ktt"));
		assertFalse(m("Fullbright", "fh"));
		assertFalse(m("Velocity", "vy"));
		assertFalse(m("Step", "sp"));
		assertFalse(m("No Render", "a"));
	}

	@Test
	void ranksTighterMatchesFirst() {
		List<String> names = List.of("Auto Totem", "Auto Eat", "Auto Reconnect", "Tracers", "Totem Pop");
		List<String> sorted = names.stream().filter(n -> m(n, "tot"))
			.sorted(Comparator.comparingInt(n -> -FuzzyMatch.score(n, "tot"))).toList();
		assertEquals(List.of("Totem Pop", "Auto Totem"), sorted);
		assertTrue(FuzzyMatch.score("Flight", "fl") > FuzzyMatch.score("Fullbright", "fl"));
		assertTrue(FuzzyMatch.score("ESP", "esp") > FuzzyMatch.score("Theme: Espresso", "esp"));
	}
}
