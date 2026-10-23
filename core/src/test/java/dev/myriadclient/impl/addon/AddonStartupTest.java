package dev.myriadclient.impl.addon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Addons window's startup line: both phases and their sum, tenths under 10 ms, and a failed addon's time to failing. */
class AddonStartupTest {
	@Test
	void startedShowsTheTotalAndBothPhases() {
		assertEquals("Started in 38 ms (initialize 36 ms, postInitialize 2.1 ms)",
			new AddonLoader.Startup(35_900_000, 2_100_000).describe(false));
		assertEquals("Started in 0.3 ms (initialize 0.3 ms, postInitialize 0.0 ms)",
			new AddonLoader.Startup(300_000, 20_000).describe(false));
	}

	@Test
	void aFailedAddonShowsHowLongItRan() {
		assertEquals("Ran 12 ms before failing (initialize 12 ms)", new AddonLoader.Startup(12_000_000, 0).describe(true));
	}
}
