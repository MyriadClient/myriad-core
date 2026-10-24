package dev.myriadclient.impl;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.harness.Harness;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Safe mode turns off what's on (as {@code .panic} does), leaves a mirror alone, and says which it turned off. */
class SafeModeTurnOffTest {
	static class Plain extends Module {
		Plain(String name) {
			super(Tags.MISC, name, "A module.");
			// Its on/off line in chat needs the HUD, which the harness doesn't have.
			chatFeedback.set(false);
		}
	}

	static final class Mirror extends Plain {
		Mirror() {
			super("Mirror");
		}

		@Override
		public boolean isMirror() {
			return true;
		}
	}

	@Test
	void turnsOffWhatsOnButAMirror() {
		try (Harness h = Harness.start()) {
			Plain on = new Plain("On"), off = new Plain("Off");
			Mirror mirror = new Mirror();
			for (Module m : List.of(on, off, mirror)) Myriad.modules().register(m, "myriad-test");
			on.setEnabled(true);
			mirror.setEnabled(true);
			List<Module> were = SafeMode.turnOff(Myriad.modules().values());
			assertEquals(List.of(on), were);
			assertFalse(on.isEnabled());
			assertFalse(off.isEnabled());
			assertTrue(mirror.isEnabled(), "a mirror's state is the other mod's");
		}
	}
}
