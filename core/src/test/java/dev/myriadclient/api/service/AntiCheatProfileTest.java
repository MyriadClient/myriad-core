package dev.myriadclient.api.service;

import dev.myriadclient.api.hook.Match;
import dev.myriadclient.api.service.AntiCheat.Profile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Profiles are values: presets, a copy under a new id, one answer changed at a time, the same profile by id. */
class AntiCheatProfileTest {
	private static final Profile LOOSE_GRIM = Profile.GRIM.named("my-addon:loose", "Loose Grim").withSimulatesGlide(false).withVisibleFacesOnly(false);

	@Test
	void aCopyKeepsTheAnswersItWasntToldToChange() {
		assertEquals("my-addon:loose", LOOSE_GRIM.id().toString());
		assertEquals("Loose Grim", LOOSE_GRIM.name());
		assertTrue(LOOSE_GRIM.simulatesMovement());
		assertTrue(LOOSE_GRIM.checksLookOnActions());
		assertTrue(LOOSE_GRIM.clicksOnlyWhileStill());
		assertFalse(LOOSE_GRIM.simulatesGlide());
		assertFalse(LOOSE_GRIM.visibleFacesOnly());
		assertEquals(AntiCheat.BreakTiming.CHECKED, LOOSE_GRIM.breakTiming());
		assertEquals(9, LOOSE_GRIM.interactionBudget());
		assertEquals(1, Profile.VANILLA.withInteractionBudget(0).interactionBudget());
	}

	@Test
	void profilesAreTheSameByIdAndMatchSoInHooks() {
		assertEquals(Profile.GRIM, Profile.GRIM.withSimulatesGlide(false));
		assertNotEquals(Profile.GRIM, LOOSE_GRIM);
		assertNotEquals(Profile.VANILLA, Profile.NCP);
		Match m = Match.always().on(Profile.GRIM);
		assertTrue(m.testWhere(Profile.GRIM.withVisibleFacesOnly(false), null));
		assertFalse(m.testWhere(LOOSE_GRIM, null));
		assertEquals("on Grim", m.toString());
		assertEquals(m, Match.always().on(Profile.GRIM.withSimulatesGlide(false)));
	}

	@Test
	void theOldModeReadsAsTheNearestProfile() {
		AntiCheat ac = new FixedChoice(LOOSE_GRIM);
		assertSame(AntiCheat.Mode.GRIM, ac.mode());
		assertSame(AntiCheat.Mode.VANILLA, new FixedChoice(Profile.NCP).mode());
		assertSame(AntiCheat.Mode.AUTO, new FixedChoice(null).mode());
		assertTrue(ac.isStrict());
	}

	/** Just enough of the service for the default methods. */
	@SuppressWarnings("deprecation")
	private record FixedChoice(Profile choice) implements AntiCheat {
		public Profile profile() {
			return choice == null ? Profile.VANILLA : choice;
		}

		public Profile detected() {
			return Profile.VANILLA;
		}

		public void choose(Profile profile) {
		}

		public dev.myriadclient.api.registry.Registry<Profile> profiles() {
			return null;
		}

		public java.util.Set<String> knownServers() {
			return java.util.Set.of();
		}

		public boolean addKnownServer(String host) {
			return false;
		}

		public boolean removeKnownServer(String host) {
			return false;
		}

		public int joinedProtocol() {
			return -1;
		}

		public String joinedVersion() {
			return "unknown";
		}

		public boolean isTranslated() {
			return false;
		}
	}
}
