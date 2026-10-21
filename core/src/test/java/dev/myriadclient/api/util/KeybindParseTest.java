package dev.myriadclient.api.util;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeybindParseTest {
	@Test
	void parsesKeysModifiersAndMouseButtons() {
		assertEquals(Keybind.key(GLFW.GLFW_KEY_T), Keybind.parse("t"));
		assertEquals(Keybind.key(GLFW.GLFW_KEY_T, GLFW.GLFW_MOD_CONTROL), Keybind.parse("Ctrl+T"));
		assertEquals(Keybind.key(GLFW.GLFW_KEY_F6, GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_ALT), Keybind.parse("shift+alt+f6"));
		assertEquals(Keybind.mouse(4), Keybind.parse("mouse5"));
		assertEquals(new Keybind(GLFW.GLFW_MOUSE_BUTTON_RIGHT, true, GLFW.GLFW_MOD_SHIFT), Keybind.parse("shift+rmb"));
		assertEquals(Keybind.NONE, Keybind.parse("none"));
		assertNull(Keybind.parse("hyper+t"));
		assertNull(Keybind.parse("notakey"));
	}

	@Test
	void takesAnyNumberOfModifiersAndShortKeyNames() {
		int ctrlAlt = GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT;
		assertEquals(Keybind.key(GLFW.GLFW_KEY_T, ctrlAlt), Keybind.parse("ctrl+alt+t"));
		assertEquals(Keybind.key(GLFW.GLFW_KEY_PAGE_DOWN, ctrlAlt), Keybind.parse("ctrl+alt+pgdown"));
		assertEquals(Keybind.key(GLFW.GLFW_KEY_PAGE_UP, ctrlAlt | GLFW.GLFW_MOD_SHIFT), Keybind.parse("Ctrl+Alt+Shift+PgUp"));
		assertEquals(Keybind.key(GLFW.GLFW_KEY_DELETE, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER), Keybind.parse("ctrl+super+del"));
		// Same as the long names Minecraft uses.
		assertEquals(Keybind.parse("ctrl+alt+page.down"), Keybind.parse("ctrl+alt+pgdown"));
	}

	@Test
	void aModifierCanBeTheKeyItself() {
		assertEquals(Keybind.key(GLFW.GLFW_KEY_LEFT_ALT), Keybind.parse("alt"));
		assertEquals(Keybind.key(GLFW.GLFW_KEY_RIGHT_SHIFT), Keybind.parse("rshift"));
		assertEquals(Keybind.key(GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_MOD_CONTROL), Keybind.parse("ctrl+alt"));
		assertEquals(Keybind.key(GLFW.GLFW_KEY_LEFT_SHIFT), Keybind.parse("left.shift"));
		// Pressing it matches, whatever modifier bits the key itself sets.
		assertTrue(Keybind.parse("alt").matchesKey(GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_MOD_ALT));
	}

	@Test
	void lockKeysAreNeverPartOfABind() {
		// Pressed with Num Lock or Caps Lock on, the bind is still just Ctrl+T, and matches without them.
		Keybind bind = Keybind.key(GLFW.GLFW_KEY_T, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_NUM_LOCK | GLFW.GLFW_MOD_CAPS_LOCK);
		assertEquals(Keybind.key(GLFW.GLFW_KEY_T, GLFW.GLFW_MOD_CONTROL), bind);
		assertEquals("key:84:" + GLFW.GLFW_MOD_CONTROL, bind.serialize());
	}
}
