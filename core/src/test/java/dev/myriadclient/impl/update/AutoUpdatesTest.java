package dev.myriadclient.impl.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoUpdatesTest {
	@TempDir
	Path dir;

	@Test
	void offUntilTurnedOnAndRemembered() {
		AutoUpdates a = new AutoUpdates(dir);
		assertFalse(a.on("addon"));
		assertFalse(a.any());
		a.set("addon", true);
		assertTrue(new AutoUpdates(dir).on("addon"));
		a.set("addon", false);
		assertFalse(new AutoUpdates(dir).on("addon"));
	}

	@Test
	void oddIdsAreIgnored() throws Exception {
		Files.writeString(dir.resolve("auto.json"), "{\"addons\":[\"../evil\", 5, \"good\"]}");
		AutoUpdates a = new AutoUpdates(dir);
		assertTrue(a.on("good"));
		assertFalse(a.on("../evil"));
		a.set("bad id!", true);
		assertFalse(a.on("bad id!"));
	}
}
