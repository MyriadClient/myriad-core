package dev.myriadclient.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** {@link SafeMode}: two early crashes in a row start the next run safe; a clean stop or a minute's run clears the streak. */
class SafeModeTest {
	@TempDir
	Path dir;

	private Path file() {
		return dir.resolve("myriad").resolve(".running");
	}

	@Test
	void aFirstStartIsntSafe() {
		SafeMode run = new SafeMode(file(), 0);
		assertFalse(run.active());
		assertEquals(0, run.earlyCrashes());
		assertTrue(Files.exists(file()));
	}

	@Test
	void twoEarlyCrashesInARowStartTheNextRunSafe() {
		new SafeMode(file(), 0);                    // crashes ten seconds in
		SafeMode second = new SafeMode(file(), 10_000);
		assertEquals(1, second.earlyCrashes());
		assertFalse(second.active());
		second.tick(10_000 + SafeMode.SURVIVED_MS - 1);  // crashes just short of a minute
		SafeMode third = new SafeMode(file(), 100_000);
		assertEquals(2, third.earlyCrashes());
		assertTrue(third.active());
		// Still crashing in safe mode: the next one is safe too.
		assertTrue(new SafeMode(file(), 110_000).active());
	}

	@Test
	void aRunThatLastsAMinuteEndsTheStreakHoweverItEnds() {
		new SafeMode(file(), 0);
		SafeMode second = new SafeMode(file(), 10_000);
		second.tick(10_000 + SafeMode.SURVIVED_MS);
		// It then crashes: not early.
		SafeMode third = new SafeMode(file(), 200_000);
		assertEquals(0, third.earlyCrashes());
		assertFalse(third.active());
	}

	@Test
	void aCleanStopEndsTheStreak() {
		new SafeMode(file(), 0);
		SafeMode second = new SafeMode(file(), 10_000);
		second.stopped();
		assertFalse(Files.exists(file()));
		assertEquals(0, new SafeMode(file(), 20_000).earlyCrashes());
	}

	@Test
	void anUnreadableFileCountsAsOneCrash() throws Exception {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "{not json");
		assertEquals(1, new SafeMode(file(), 0).earlyCrashes());
	}
}
