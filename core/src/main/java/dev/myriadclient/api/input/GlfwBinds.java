package dev.myriadclient.api.input;

import com.mojang.blaze3d.platform.InputConstants;

/** Reads binds saved before 26.3, when input came from GLFW: its key, mouse button and modifier numbers, as today's. */
final class GlfwBinds {
	private GlfwBinds() {
	}

	/** Today's key for GLFW's key {@code code}; the unset key if there's no such key now. */
	static int key(int code) {
		if (code >= 65 && code <= 90) return InputConstants.KEY_A + code - 65;
		if (code >= 49 && code <= 57) return InputConstants.KEY_1 + code - 49;
		if (code >= 290 && code <= 301) return InputConstants.KEY_F1 + code - 290;
		if (code >= 302 && code <= 313) return InputConstants.KEY_F13 + code - 302;
		if (code >= 321 && code <= 329) return InputConstants.KEY_NUMPAD1 + code - 321;
		return switch (code) {
			case 32 -> InputConstants.KEY_SPACE;
			case 39 -> InputConstants.KEY_APOSTROPHE;
			case 44 -> InputConstants.KEY_COMMA;
			case 45 -> InputConstants.KEY_MINUS;
			case 46 -> InputConstants.KEY_PERIOD;
			case 47 -> InputConstants.KEY_SLASH;
			case 48 -> InputConstants.KEY_0;
			case 59 -> InputConstants.KEY_SEMICOLON;
			case 61 -> InputConstants.KEY_EQUALS;
			case 91 -> InputConstants.KEY_LBRACKET;
			case 92 -> InputConstants.KEY_BACKSLASH;
			case 93 -> InputConstants.KEY_RBRACKET;
			case 96 -> InputConstants.KEY_GRAVE;
			case 256 -> InputConstants.KEY_ESCAPE;
			case 257 -> InputConstants.KEY_RETURN;
			case 258 -> InputConstants.KEY_TAB;
			case 259 -> InputConstants.KEY_BACKSPACE;
			case 260 -> InputConstants.KEY_INSERT;
			case 261 -> InputConstants.KEY_DELETE;
			case 262 -> InputConstants.KEY_RIGHT;
			case 263 -> InputConstants.KEY_LEFT;
			case 264 -> InputConstants.KEY_DOWN;
			case 265 -> InputConstants.KEY_UP;
			case 266 -> InputConstants.KEY_PAGEUP;
			case 267 -> InputConstants.KEY_PAGEDOWN;
			case 268 -> InputConstants.KEY_HOME;
			case 269 -> InputConstants.KEY_END;
			case 280 -> InputConstants.KEY_CAPSLOCK;
			case 281 -> InputConstants.KEY_SCROLLLOCK;
			case 282 -> InputConstants.KEY_NUMLOCK;
			case 283 -> InputConstants.KEY_PRINTSCREEN;
			case 284 -> InputConstants.KEY_PAUSE;
			case 320 -> InputConstants.KEY_NUMPAD0;
			case 332 -> InputConstants.KEY_MULTIPLY;
			case 334 -> InputConstants.KEY_ADD;
			case 335 -> InputConstants.KEY_NUMPADENTER;
			case 336 -> InputConstants.KEY_NUMPADEQUALS;
			case 340 -> InputConstants.KEY_LSHIFT;
			case 341 -> InputConstants.KEY_LCONTROL;
			case 342 -> InputConstants.KEY_LALT;
			case 343 -> InputConstants.KEY_LGUI;
			case 344 -> InputConstants.KEY_RSHIFT;
			case 345 -> InputConstants.KEY_RCONTROL;
			case 346 -> InputConstants.KEY_RALT;
			case 347 -> InputConstants.KEY_RGUI;
			default -> Keybind.NONE.code();
		};
	}

	/** Today's mouse button for GLFW's: left 0, right 1, middle 2, then the side buttons. */
	static int button(int button) {
		return switch (button) {
			case 0 -> InputConstants.MOUSE_BUTTON_LEFT;
			case 1 -> InputConstants.MOUSE_BUTTON_RIGHT;
			case 2 -> InputConstants.MOUSE_BUTTON_MIDDLE;
			default -> button + 1;
		};
	}

	static int modifiers(int mods) {
		return ((mods & 1) != 0 ? InputConstants.MOD_SHIFT : 0)
			| ((mods & 2) != 0 ? InputConstants.MOD_CONTROL : 0)
			| ((mods & 4) != 0 ? InputConstants.MOD_ALT : 0)
			| ((mods & 8) != 0 ? InputConstants.MOD_SUPER : 0);
	}
}
