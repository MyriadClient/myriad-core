package dev.myriadclient.impl.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotificationCoordsTest {
	@Test
	void hidesCoordinatesInEveryForm() {
		assertEquals("(location hidden)", NotificationManager.withoutCoords("-1999, 103, -2000"));
		assertEquals("Box left at (location hidden)", NotificationManager.withoutCoords("Box left at 120 64 -3500"));
		assertEquals("Landed at (location hidden), 40% flown", NotificationManager.withoutCoords("Landed at 1234.5 70.0 -88.2, 40% flown"));
	}

	@Test
	void leavesOtherNumbersAlone() {
		assertEquals("3 totem pops", NotificationManager.withoutCoords("3 totem pops"));
		assertEquals("health 20.0", NotificationManager.withoutCoords("health 20.0"));
		assertEquals("1-12 of 54 boxes", NotificationManager.withoutCoords("1-12 of 54 boxes"));
		assertEquals("Myriad 1.21.5", NotificationManager.withoutCoords("Myriad 1.21.5"));
		assertEquals("2 of 5", NotificationManager.withoutCoords("2 of 5"));
	}
}
