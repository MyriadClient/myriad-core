package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.api.util.Keybind;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Click, then press a key or mouse button (middle/side) to bind, with any modifiers held. A modifier pressed and let go
 * on its own binds that key (Left Alt). Escape cancels, Backspace/Delete unbinds.
 */
public class KeybindButton extends Widget {
	private final Supplier<Keybind> getter;
	private final Consumer<Keybind> setter;
	private boolean listening;
	/** A modifier pressed while listening, bound on its own if it's let go before any other key; -1 if none. */
	private int modifierKey = -1, modifierMods;

	public KeybindButton(Supplier<Keybind> getter, Consumer<Keybind> setter) {
		this.getter = getter;
		this.setter = setter;
	}

	@Override
	protected float measure(Canvas canvas, float width) {
		return ROW;
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		if (listening && !isFocused()) listening = false;
		// Let go with nothing else pressed: the modifier itself is the bind (with any other modifiers held then).
		if (listening && modifierKey >= 0 && GLFW.glfwGetKey(Minecraft.getInstance().getWindow().handle(), modifierKey) != GLFW.GLFW_PRESS) {
			setter.accept(Keybind.key(modifierKey, modifierMods));
			stop();
		}
		boolean hover = isHovered(mx, my);
		c.roundRect(x, y, width, height, 4, listening ? ColorUtil.withAlpha(theme().accent.argb(), 120) : hover ? theme().surfaceHover.argb() : theme().surface.argb());
		String text = listening ? "Press a key…" : getter.get().displayName();
		c.text(text, x + (width - c.textWidth(text)) / 2, y + (height - c.textHeight()) / 2, theme().text.argb());
		offerTooltip(mx, my);
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (listening) {
			if (button >= GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
				setter.accept(new Keybind(button, true, heldModifiers()));
				stop();
				return true;
			}
			if (!isHovered(mx, my)) {
				stop();
				return false;
			}
			return true;
		}
		if (!isHovered(mx, my)) return false;
		if (button == 0) {
			listening = true;
			focus();
		} else if (button == 1) {
			setter.accept(Keybind.NONE);
		}
		return true;
	}

	/** The modifier keys held now, for a mouse button pressed with them (Ctrl+Mouse5). */
	private static int heldModifiers() {
		long w = Minecraft.getInstance().getWindow().handle();
		int mods = 0;
		if (held(w, GLFW.GLFW_KEY_LEFT_CONTROL) || held(w, GLFW.GLFW_KEY_RIGHT_CONTROL)) mods |= GLFW.GLFW_MOD_CONTROL;
		if (held(w, GLFW.GLFW_KEY_LEFT_ALT) || held(w, GLFW.GLFW_KEY_RIGHT_ALT)) mods |= GLFW.GLFW_MOD_ALT;
		if (held(w, GLFW.GLFW_KEY_LEFT_SHIFT) || held(w, GLFW.GLFW_KEY_RIGHT_SHIFT)) mods |= GLFW.GLFW_MOD_SHIFT;
		if (held(w, GLFW.GLFW_KEY_LEFT_SUPER) || held(w, GLFW.GLFW_KEY_RIGHT_SUPER)) mods |= GLFW.GLFW_MOD_SUPER;
		return mods;
	}

	private static boolean held(long window, int key) {
		return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
	}

	@Override
	public boolean isNavigable() {
		return true;
	}

	@Override
	public void activate() {
		listening = true;
		focus();
	}

	private void stop() {
		listening = false;
		modifierKey = -1;
		blur();
	}

	/** The modifier flag a modifier key sets, so a bind to that key alone doesn't also require it. */
	private static int modifierBit(int key) {
		return switch (key) {
			case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> GLFW.GLFW_MOD_SHIFT;
			case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> GLFW.GLFW_MOD_CONTROL;
			case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> GLFW.GLFW_MOD_ALT;
			case GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER -> GLFW.GLFW_MOD_SUPER;
			default -> 0;
		};
	}

	@Override
	public boolean keyPressed(int key, int scancode, int mods) {
		if (!listening) return false;
		switch (key) {
			case GLFW.GLFW_KEY_ESCAPE -> {
			}
			case GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_DELETE -> setter.accept(Keybind.NONE);
			case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL,
				 GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT, GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER -> {
				// Wait for a non-modifier key (held modifiers are recorded with it), or for this one to be let go alone.
				modifierKey = key;
				modifierMods = mods & ~modifierBit(key);
				return true;
			}
			default -> setter.accept(Keybind.key(key, mods));
		}
		stop();
		return true;
	}

	@Override
	protected void onBlur() {
		listening = false;
	}
}
