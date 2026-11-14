package dev.myriadclient.api.input;

import com.mojang.blaze3d.platform.InputConstants;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLMouse;

import java.nio.FloatBuffer;
import java.util.Locale;

/**
 * A key or mouse button with optional modifiers ({@code InputConstants.MOD_*} bits). {@code code} is an
 * {@code InputConstants.KEY_*} value (a key's position), or for a mouse bind an {@code InputConstants.MOUSE_BUTTON_*} one
 * (left is 1). A held modifier counts either way round: a bind on {@code MOD_SHIFT} matches left or right Shift.
 * Settings store binds as {@link dev.myriadclient.api.setting.KeybindSetting}s; {@link #parse} reads what a player types and {@link #serialize} /
 * {@link #deserialize} what config saves.
 *
 * <pre>{@code
 * Keybind open = Keybind.key(InputConstants.KEY_O, InputConstants.MOD_CONTROL);   // Ctrl+O
 * if (open.matchesKey(event.key(), event.modifiers())) ...;
 * }</pre>
 *
 * @param code      the key or mouse button; 0 for no bind ({@link #NONE})
 * @param mouse     whether {@code code} is a mouse button rather than a key
 * @param modifiers the {@code InputConstants.MOD_*} bits that must be held, both sides of each set ({@link #pairs})
 */
public record Keybind(int code, boolean mouse, int modifiers) {
	private static final int[] PAIRS = {InputConstants.MOD_CONTROL, InputConstants.MOD_ALT, InputConstants.MOD_SHIFT, InputConstants.MOD_SUPER};
	private static final int NONE_CODE = 0;
	/** No bind: matches nothing and shows as "None". */
	public static final Keybind NONE = new Keybind(NONE_CODE, false, 0);
	/** The modifiers a bind can hold, any number of them (Ctrl+Alt+T): not Caps Lock or Num Lock. */
	public static final int MODIFIERS = InputConstants.MOD_CONTROL | InputConstants.MOD_ALT | InputConstants.MOD_SHIFT | InputConstants.MOD_SUPER;

	/**
	 * The modifier keys held right now ({@code InputConstants.MOD_} bits of {@link #MODIFIERS}), either side counting:
	 * for input that doesn't carry its own (typed characters, a mouse button pressed with Ctrl).
	 */
	@ApiStatus.AvailableSince("2.0")
	public static int heldModifiers() {
		int mods = 0;
		if (InputConstants.isKeyDown(InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL)) mods |= InputConstants.MOD_CONTROL;
		if (InputConstants.isKeyDown(InputConstants.KEY_LALT) || InputConstants.isKeyDown(InputConstants.KEY_RALT)) mods |= InputConstants.MOD_ALT;
		if (InputConstants.isKeyDown(InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT)) mods |= InputConstants.MOD_SHIFT;
		if (InputConstants.isKeyDown(InputConstants.KEY_LGUI) || InputConstants.isKeyDown(InputConstants.KEY_RGUI)) mods |= InputConstants.MOD_SUPER;
		return mods;
	}

	/** Keeps {@code modifiers} as {@link #pairs} gives them, so either side of a modifier matches. */
	public Keybind {
		modifiers = pairs(modifiers);
	}

	/**
	 * {@code mods} (from an input event, which sets only the side that's held) with both sides of each held modifier
	 * set.
	 */
	@ApiStatus.AvailableSince("2.0")
	public static int pairs(int mods) {
		int all = 0;
		for (int pair : PAIRS) if ((mods & pair) != 0) all |= pair;
		return all;
	}

	/** A bind on the key {@code key} ({@code InputConstants.KEY_*}) with no modifiers. */
	public static Keybind key(int key) {
		return new Keybind(key, false, 0);
	}

	/** A bind on the key {@code key} with {@code modifiers} ({@code InputConstants.MOD_*} bits) held. */
	public static Keybind key(int key, int modifiers) {
		return new Keybind(key, false, modifiers);
	}

	/**
	 * A bind on the mouse button {@code button} ({@code InputConstants.MOUSE_BUTTON_*}, left is 1) with no modifiers.
	 */
	public static Keybind mouse(int button) {
		return new Keybind(button, true, 0);
	}

	/** Whether this is a bind at all (not {@link #NONE}). */
	public boolean isSet() {
		return code != NONE_CODE;
	}

	/**
	 * Whether a key event for {@code key} with {@code mods} held (as the event gives them) is this bind: a key bind on that
	 * key, with every modifier of the bind held. Other modifiers may be held too, so Ctrl+Shift+T matches a Ctrl+T bind.
	 */
	public boolean matchesKey(int key, int mods) {
		return isSet() && !mouse && code == key && (pairs(mods) & modifiers) == modifiers;
	}

	/** Whether the key or button is held down right now (modifiers are not checked). */
	public boolean isPressed() {
		if (!isSet()) return false;
		return mouse ? (SDLMouse.SDL_GetMouseState((FloatBuffer) null, null) & 1 << code - 1) != 0 : InputConstants.isKeyDown(code);
	}

	/** {@link #matchesKey} for a mouse button event: a mouse bind on {@code button}, with its modifiers held. */
	public boolean matchesMouse(int button, int mods) {
		return isSet() && mouse && code == button && (pairs(mods) & modifiers) == modifiers;
	}

	/**
	 * The bind for people, modifiers first: {@code "Ctrl+Shift+T"}, {@code "RMB"}, {@code "Mouse5"}, {@code "None"}. Key
	 * names follow the game's language and the player's keyboard layout.
	 */
	public String displayName() {
		if (!isSet()) return "None";
		StringBuilder sb = new StringBuilder();
		if ((modifiers & InputConstants.MOD_CONTROL) != 0) sb.append("Ctrl+");
		if ((modifiers & InputConstants.MOD_ALT) != 0) sb.append("Alt+");
		if ((modifiers & InputConstants.MOD_SHIFT) != 0) sb.append("Shift+");
		if ((modifiers & InputConstants.MOD_SUPER) != 0) sb.append("Super+");
		if (mouse) {
			sb.append(switch (code) {
				case InputConstants.MOUSE_BUTTON_LEFT -> "LMB";
				case InputConstants.MOUSE_BUTTON_RIGHT -> "RMB";
				case InputConstants.MOUSE_BUTTON_MIDDLE -> "MMB";
				default -> "Mouse" + code;
			});
		} else {
			sb.append(keyName(code));
		}
		return sb.toString();
	}

	/**
	 * {@code key} (a key's position) as the letter it types on the player's keyboard layout, for letter shortcuts, so
	 * Ctrl+V is the key labelled V: the {@code KEY_} constant that letter has on a US layout; other keys unchanged.
	 */
	@ApiStatus.AvailableSince("2.0")
	public static int letter(int key) {
		int typed = SDLKeyboard.SDL_GetKeyFromScancode(key, (short) 0, false);
		return typed >= 'a' && typed <= 'z' ? InputConstants.KEY_A + typed - 'a' : key;
	}

	/**
	 * The game's name for the key {@code key} in its language ({@code "T"}, {@code "Left Shift"}), or {@code "Key
	 * <n>"}.
	 */
	public static String keyName(int key) {
		String translated = InputConstants.Type.KEYBOARD.getOrCreate(key).getDisplayName().getString();
		return translated.startsWith("key.keyboard.") ? "Key " + key : translated;
	}

	/**
	 * A bind written the way {@link #displayName()} shows it, in any case: {@code "t"}, {@code "ctrl+t"},
	 * {@code "ctrl+alt+pgdown"}, {@code "shift+alt+f6"}, a modifier on its own ({@code "alt"}, {@code "rshift"}),
	 * {@code "mouse5"}, {@code "rmb"};
	 * {@code "none"} for no bind. Null if it isn't one.
	 */
	public static @Nullable Keybind parse(String input) {
		String in = input.trim().toLowerCase(Locale.ROOT);
		if (in.equals("none") || in.equals("unbind")) return NONE;
		String[] parts = in.split("\\+");
		int mods = 0;
		for (int i = 0; i < parts.length - 1; i++) {
			mods |= switch (parts[i].trim()) {
				case "ctrl", "control" -> InputConstants.MOD_CONTROL;
				case "alt" -> InputConstants.MOD_ALT;
				case "shift" -> InputConstants.MOD_SHIFT;
				case "super", "win", "cmd" -> InputConstants.MOD_SUPER;
				default -> -1;
			};
			if (mods < 0) return null;
		}
		String key = parts[parts.length - 1].trim();
		switch (key) {
			case "lmb" -> {
				return new Keybind(InputConstants.MOUSE_BUTTON_LEFT, true, mods);
			}
			case "rmb" -> {
				return new Keybind(InputConstants.MOUSE_BUTTON_RIGHT, true, mods);
			}
			case "mmb" -> {
				return new Keybind(InputConstants.MOUSE_BUTTON_MIDDLE, true, mods);
			}
			default -> {
			}
		}
		// "mouse4" counts buttons as binds saved before 26.3 did (left, right, middle, then the side buttons).
		if (key.matches("mouse[1-8]")) return new Keybind(GlfwBinds.button(Integer.parseInt(key.substring(5)) - 1), true, mods);
		key = switch (key) {
			case "pgdown", "pgdn", "pagedown" -> "page.down";
			case "pgup", "pageup" -> "page.up";
			case "del" -> "delete";
			case "ins" -> "insert";
			case "esc" -> "escape";
			case "return" -> "enter";
			case "bksp" -> "backspace";
			case "caps", "capslock" -> "caps.lock";
			case "numlock" -> "num.lock";
			case "scrolllock" -> "scroll.lock";
			case "prtsc", "printscreen" -> "print.screen";
			// A modifier on its own as the key (left one unless told): "alt", "ctrl+rshift".
			case "alt", "lalt", "leftalt" -> "left.alt";
			case "ralt", "rightalt" -> "right.alt";
			case "ctrl", "control", "lctrl", "leftctrl" -> "left.control";
			case "rctrl", "rightctrl" -> "right.control";
			case "shift", "lshift", "leftshift" -> "left.shift";
			case "rshift", "rightshift" -> "right.shift";
			case "super", "win", "cmd", "lsuper", "lwin" -> "left.win";
			case "rsuper", "rwin" -> "right.win";
			default -> key;
		};
		try {
			InputConstants.Key k = InputConstants.getKey("key.keyboard." + key);
			return k.getValue() < 0 ? null : new Keybind(k.getValue(), false, mods);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Serialised as {@code "sdl-key:4:0"} / {@code "sdl-mouse:1:0"}; {@code "key:65:0"} / {@code "mouse:0:0"} are binds
	 * saved before 26.3.
	 */
	public String serialize() {
		return (mouse ? "sdl-mouse:" : "sdl-key:") + code + ":" + modifiers;
	}

	/** Reads a bind {@link #serialize} wrote, binds saved before 26.3 included; {@link #NONE} for anything else. */
	public static Keybind deserialize(String s) {
		try {
			String[] p = s.split(":");
			int code = Integer.parseInt(p[1]), mods = p.length > 2 ? Integer.parseInt(p[2]) : 0;
			return switch (p[0]) {
				case "sdl-key" -> new Keybind(code, false, mods);
				case "sdl-mouse" -> new Keybind(code, true, mods);
				case "key" -> new Keybind(GlfwBinds.key(code), false, GlfwBinds.modifiers(mods));
				case "mouse" -> new Keybind(GlfwBinds.button(code), true, GlfwBinds.modifiers(mods));
				default -> NONE;
			};
		} catch (RuntimeException e) {
			return NONE;
		}
	}

	/** {@link #displayName()}. */
	@Override
	public String toString() {
		return displayName();
	}
}
