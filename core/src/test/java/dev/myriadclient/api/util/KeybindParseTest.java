package dev.myriadclient.api.util;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
