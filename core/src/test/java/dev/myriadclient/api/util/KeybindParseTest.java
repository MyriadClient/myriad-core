package dev.myriadclient.api.util;

import com.mojang.blaze3d.platform.InputConstants;
import dev.myriadclient.api.input.Keybind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeybindParseTest {
	@Test
	void parsesKeysModifiersAndMouseButtons() {
		assertEquals(Keybind.key(InputConstants.KEY_T), Keybind.parse("t"));
		assertEquals(Keybind.key(InputConstants.KEY_T, InputConstants.MOD_CONTROL), Keybind.parse("Ctrl+T"));
		assertEquals(Keybind.key(InputConstants.KEY_F6, InputConstants.MOD_SHIFT | InputConstants.MOD_ALT), Keybind.parse("shift+alt+f6"));
		assertEquals(Keybind.mouse(InputConstants.MOUSE_BUTTON_5), Keybind.parse("mouse5"));
		assertEquals(new Keybind(InputConstants.MOUSE_BUTTON_RIGHT, true, InputConstants.MOD_SHIFT), Keybind.parse("shift+rmb"));
		assertEquals(Keybind.NONE, Keybind.parse("none"));
		assertNull(Keybind.parse("hyper+t"));
		assertNull(Keybind.parse("notakey"));
	}

	@Test
	void takesAnyNumberOfModifiersAndShortKeyNames() {
		int ctrlAlt = InputConstants.MOD_CONTROL | InputConstants.MOD_ALT;
		assertEquals(Keybind.key(InputConstants.KEY_T, ctrlAlt), Keybind.parse("ctrl+alt+t"));
		assertEquals(Keybind.key(InputConstants.KEY_PAGEDOWN, ctrlAlt), Keybind.parse("ctrl+alt+pgdown"));
		assertEquals(Keybind.key(InputConstants.KEY_PAGEUP, ctrlAlt | InputConstants.MOD_SHIFT), Keybind.parse("Ctrl+Alt+Shift+PgUp"));
		assertEquals(Keybind.key(InputConstants.KEY_DELETE, InputConstants.MOD_CONTROL | InputConstants.MOD_SUPER), Keybind.parse("ctrl+super+del"));
		// Same as the long names Minecraft uses.
		assertEquals(Keybind.parse("ctrl+alt+page.down"), Keybind.parse("ctrl+alt+pgdown"));
	}

	@Test
	void aModifierCanBeTheKeyItself() {
		assertEquals(Keybind.key(InputConstants.KEY_LALT), Keybind.parse("alt"));
		assertEquals(Keybind.key(InputConstants.KEY_RSHIFT), Keybind.parse("rshift"));
		assertEquals(Keybind.key(InputConstants.KEY_LALT, InputConstants.MOD_CONTROL), Keybind.parse("ctrl+alt"));
		assertEquals(Keybind.key(InputConstants.KEY_LSHIFT), Keybind.parse("left.shift"));
		// Pressing it matches, whatever modifier bits the key itself sets.
		assertTrue(Keybind.parse("alt").matchesKey(InputConstants.KEY_LALT, InputConstants.MOD_ALT));
	}

	@Test
	void lockKeysAreNeverPartOfABind() {
		// Pressed with Num Lock or Caps Lock on, the bind is still just Ctrl+T, and matches without them.
		Keybind bind = Keybind.key(InputConstants.KEY_T, InputConstants.MOD_CONTROL | InputConstants.MOD_NUM_LOCK | InputConstants.MOD_CAPS_LOCK);
		assertEquals(Keybind.key(InputConstants.KEY_T, InputConstants.MOD_CONTROL), bind);
		assertEquals("sdl-key:" + InputConstants.KEY_T + ":" + InputConstants.MOD_CONTROL, bind.serialize());
	}

	@Test
	void bindsSavedBefore263AreReadInTodaysNumbers() {
		assertEquals(Keybind.key(InputConstants.KEY_T, InputConstants.MOD_CONTROL), Keybind.deserialize("key:84:2"));
		assertEquals(Keybind.key(InputConstants.KEY_RSHIFT), Keybind.deserialize("key:344:0"));
		assertEquals(Keybind.mouse(InputConstants.MOUSE_BUTTON_RIGHT), Keybind.deserialize("mouse:1:0"));
		assertEquals(Keybind.NONE, Keybind.deserialize("key:-1:0"));
	}

	@Test
	void eitherSideOfAModifierMatches() {
		Keybind bind = Keybind.key(InputConstants.KEY_T, InputConstants.MOD_SHIFT);
		assertTrue(bind.matchesKey(InputConstants.KEY_T, 1));
		assertTrue(bind.matchesKey(InputConstants.KEY_T, 2));
		assertFalse(bind.matchesKey(InputConstants.KEY_T, 0));
	}
}
