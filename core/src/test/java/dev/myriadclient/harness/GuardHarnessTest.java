package dev.myriadclient.harness;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@link Modules#guard}: what a mixin runs for a module can fail without crashing the game, and a module that keeps failing turns off. */
class GuardHarnessTest {
	static final class Broken extends Module {
		Broken() {
			super(Tags.MISC, "Broken", "Throws from its mixin code.");
		}
	}

	private static Broken register() {
		Broken m = new Broken();
		// Its on/off line in chat needs the HUD, which the harness doesn't have.
		m.chatFeedback.set(false);
		Myriad.modules().register(m, "myriad-test");
		return m;
	}

	@Test
	void runsTheBodyAndAnswersWithIt() {
		try (Harness h = Harness.start()) {
			Broken m = register();
			m.setEnabled(true);
			int[] ran = {0};
			Modules.guard(m, () -> ran[0]++);
			assertEquals(1, ran[0]);
			assertEquals("yes", Modules.guard(m, () -> "yes", "fallback"));
			// No module (not registered, or off): nothing runs.
			Modules.guard(null, () -> ran[0]++);
			assertEquals(1, ran[0]);
			assertEquals("fallback", Modules.guard(null, () -> "yes", "fallback"));
		}
	}

	@Test
	void aModuleThatKeepsFailingTurnsOffAndGetsAFreshCountWhenTurnedBackOn() {
		try (Harness h = Harness.start()) {
			Broken m = register();
			m.setEnabled(true);
			List<Throwable> seen = new ArrayList<>();
			Modules.setGuardHook((module, t) -> seen.add(t));
			try {
				for (int i = 0; i < 49; i++) {
					assertEquals(-1, Modules.guard(m, () -> {
						throw new IllegalStateException("broken");
					}, -1));
				}
				assertTrue(m.isEnabled(), "still on after 49");
				Modules.guard(m, () -> {
					throw new IllegalStateException("broken");
				});
				assertFalse(m.isEnabled(), "off at 50");
				assertEquals(50, seen.size());

				m.setEnabled(true);
				for (int i = 0; i < 49; i++) {
					Modules.guard(m, () -> {
						throw new IllegalStateException("broken");
					});
				}
				assertTrue(m.isEnabled(), "the count started over");
			} finally {
				Modules.setGuardHook(null);
			}
		}
	}

	@Test
	void runningOutOfMemoryIsntTheModules() {
		try (Harness h = Harness.start()) {
			Broken m = register();
			m.setEnabled(true);
			assertThrows(OutOfMemoryError.class, () -> Modules.guard(m, () -> {
				throw new OutOfMemoryError("test");
			}));
			// A stack overflow in a module's own recursion is the module's.
			assertDoesNotThrow(() -> Modules.guard(m, () -> {
				throw new StackOverflowError("test");
			}));
			assertTrue(m.isEnabled());
		}
	}
}
