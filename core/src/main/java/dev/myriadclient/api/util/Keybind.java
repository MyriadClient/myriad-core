package dev.myriadclient.api.util;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * A key or mouse button with optional modifiers ({@code GLFW_MOD_*} bits).
 */
public record Keybind(int code, boolean mouse, int modifiers) {
	public static final Keybind NONE = new Keybind(GLFW.GLFW_KEY_UNKNOWN, false, 0);

	public static Keybind key(int key) {
		return new Keybind(key, false, 0);
	}

	public static Keybind key(int key, int modifiers) {
		return new Keybind(key, false, modifiers);
	}

	public static Keybind mouse(int button) {
		return new Keybind(button, true, 0);
	}

	public boolean isSet() {
		return code != GLFW.GLFW_KEY_UNKNOWN;
	}

	/** Matches a key press. Modifier keys themselves are ignored when comparing modifiers. */
	public boolean matchesKey(int key, int mods) {
		return isSet() && !mouse && code == key && (modifiers == 0 || (mods & modifiers) == modifiers);
	}

	/** Whether the key or button is held down right now (modifiers are not checked). */
	public boolean isPressed() {
		if (!isSet()) return false;
		long window = Minecraft.getInstance().getWindow().handle();
		return mouse ? GLFW.glfwGetMouseButton(window, code) == GLFW.GLFW_PRESS : InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), code);
	}

	public boolean matchesMouse(int button, int mods) {
		return isSet() && mouse && code == button && (modifiers == 0 || (mods & modifiers) == modifiers);
	}

	public String displayName() {
		if (!isSet()) return "None";
		StringBuilder sb = new StringBuilder();
		if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0) sb.append("Ctrl+");
		if ((modifiers & GLFW.GLFW_MOD_ALT) != 0) sb.append("Alt+");
		if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) sb.append("Shift+");
		if ((modifiers & GLFW.GLFW_MOD_SUPER) != 0) sb.append("Super+");
		if (mouse) {
			sb.append(switch (code) {
				case GLFW.GLFW_MOUSE_BUTTON_LEFT -> "LMB";
				case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> "RMB";
				case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> "MMB";
				default -> "Mouse" + (code + 1);
			});
		} else {
			sb.append(keyName(code));
		}
		return sb.toString();
	}

	public static String keyName(int key) {
		String glfw = GLFW.glfwGetKeyName(key, 0);
		if (glfw != null) return glfw.toUpperCase(Locale.ROOT);
		String translated = InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
		return translated.startsWith("key.keyboard.") ? "Key " + key : translated;
	}

	/**
	 * A bind written the way {@link #displayName()} shows it, in any case: {@code "t"}, {@code "ctrl+t"},
	 * {@code "shift+alt+f6"}, {@code "left.shift"}, {@code "mouse5"}, {@code "rmb"}; {@code "none"} for no bind.
	 * Null if it isn't one.
	 */
	public static @Nullable Keybind parse(String input) {
		String in = input.trim().toLowerCase(Locale.ROOT);
		if (in.equals("none") || in.equals("unbind")) return NONE;
		String[] parts = in.split("\\+");
		int mods = 0;
		for (int i = 0; i < parts.length - 1; i++) {
			mods |= switch (parts[i].trim()) {
				case "ctrl", "control" -> GLFW.GLFW_MOD_CONTROL;
				case "alt" -> GLFW.GLFW_MOD_ALT;
				case "shift" -> GLFW.GLFW_MOD_SHIFT;
				case "super", "win", "cmd" -> GLFW.GLFW_MOD_SUPER;
				default -> -1;
			};
			if (mods < 0) return null;
		}
		String key = parts[parts.length - 1].trim();
		switch (key) {
			case "lmb" -> {
				return new Keybind(GLFW.GLFW_MOUSE_BUTTON_LEFT, true, mods);
			}
			case "rmb" -> {
				return new Keybind(GLFW.GLFW_MOUSE_BUTTON_RIGHT, true, mods);
			}
			case "mmb" -> {
				return new Keybind(GLFW.GLFW_MOUSE_BUTTON_MIDDLE, true, mods);
			}
			default -> {
			}
		}
		if (key.matches("mouse[1-8]")) return new Keybind(Integer.parseInt(key.substring(5)) - 1, true, mods);
		try {
			InputConstants.Key k = InputConstants.getKey("key.keyboard." + key);
			return k.getValue() < 0 ? null : new Keybind(k.getValue(), false, mods);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Serialised as {@code "key:65:0"} / {@code "mouse:1:0"}. */
	public String serialize() {
		return (mouse ? "mouse:" : "key:") + code + ":" + modifiers;
	}

	public static Keybind deserialize(String s) {
		try {
			String[] p = s.split(":");
			return new Keybind(Integer.parseInt(p[1]), p[0].equals("mouse"), p.length > 2 ? Integer.parseInt(p[2]) : 0);
		} catch (RuntimeException e) {
			return NONE;
		}
	}

	@Override
	public String toString() {
		return displayName();
	}
}
