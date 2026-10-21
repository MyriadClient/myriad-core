package dev.myriadclient.impl.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RotationManagerTest {
	@Test
	void stepTurnsAtMostTheSpeedTheShortWayRound() {
		assertEquals(30f, RotationManager.step(0, 90, 30, true), 1e-4);
		assertEquals(90f, RotationManager.step(0, 90, 0, true), 1e-4); // 0 = instant
		assertEquals(90f, RotationManager.step(80, 90, 30, true), 1e-4); // within reach: lands exactly
		// 170 -> -170 is a 20 degree turn through 180, not 340 the other way.
		assertEquals(180f, RotationManager.step(170, -170, 10, true), 1e-4);
		// Pitch doesn't wrap.
		assertEquals(-60f, RotationManager.step(-80, 80, 20, false), 1e-4);
	}

	@Test
	void pitchLandsExactlyOnTheLimits() {
		// From 38.01183 in floats, 38.01183 + (-90 - 38.01183) is -90.0000076: past what Grim accepts.
		assertEquals(-90f, RotationManager.step(38.01183319f, -90, 0, false));
		for (float from = -89.9f; from < 90; from += 0.0137f) {
			assertEquals(-90f, RotationManager.step(from, -90, 0, false));
			assertEquals(90f, RotationManager.step(from, 90, 0, false));
		}
	}

	@Test
	void fixKeysKeepsKeysWhenTheYawMatches() {
		assertArrayEquals(new int[]{1, 0}, RotationManager.fixKeys(10, 10, true, false, false, false));
		assertArrayEquals(new int[]{1, 1}, RotationManager.fixKeys(10, 10, true, false, false, true));
	}

	@Test
	void fixKeysPicksTheKeysThatGoTheSameWayFromTheSentYaw() {
		// Looking south (0) and walking forward, while the server sees you facing west (90): forward relative to
		// west would go west, so press left (west's left is south).
		assertArrayEquals(new int[]{0, -1}, RotationManager.fixKeys(0, 90, true, false, false, false));
		// Facing the opposite way: walk backwards.
		assertArrayEquals(new int[]{-1, 0}, RotationManager.fixKeys(0, 180, true, false, false, false));
		// 45 degrees off: a diagonal gets closest.
		assertArrayEquals(new int[]{1, -1}, RotationManager.fixKeys(0, 45, true, false, false, false));
	}

	@Test
	void fixKeysWithNothingPressedPressesNothing() {
		assertArrayEquals(new int[]{0, 0}, RotationManager.fixKeys(0, 90, false, false, true, true));
	}

	@Test
	void sentYawTurnsFromTheLastOneSentNeverMoreThanHalfATurn() {
		// Holding -90 while the camera is at 120: still -90, not 270 (120's nearest form of it).
		assertEquals(-90f, RotationManager.continuous(-90, -90), 1e-4);
		// Handing back to a camera at 300 from -90: 300 is -60 the short way, so -60, not a 390 degree jump.
		assertEquals(-60f, RotationManager.continuous(-90, 300), 1e-4);
		// A camera that has spun round a few times stays where it is when it's close anyway.
		assertEquals(725f, RotationManager.continuous(720, 725), 1e-4);
	}
}
