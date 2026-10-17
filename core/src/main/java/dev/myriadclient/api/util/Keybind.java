package dev.myriadclient.api.util;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
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
