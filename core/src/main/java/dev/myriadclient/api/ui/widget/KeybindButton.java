package dev.myriadclient.api.ui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import dev.myriadclient.api.input.Keybind;
import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.util.ColorUtil;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Click, then press a key or mouse button (middle/side) to bind, with any modifiers held. A modifier pressed and let go
 * on its own binds that key (Left Alt). Escape cancels, Backspace/Delete unbinds; right-clicking it unbinds too. While
 * listening it holds keyboard focus, so the UI's shortcuts don't fire.
 */
public class KeybindButton extends Widget {
	private final Supplier<Keybind> getter;
	private final Consumer<Keybind> setter;
	private boolean listening;
	/** A modifier pressed while listening, bound on its own if it's let go before any other key; -1 if none. */
	private int modifierKey = -1, modifierMods;

	/**
	 * @param getter the current bind, read every frame
	 * @param setter called with the new bind ({@link Keybind#NONE} when unbound)
	 */
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
		if (listening && modifierKey >= 0 && !InputConstants.isKeyDown(modifierKey)) {
			setter.accept(Keybind.key(modifierKey, modifierMods));
			stop();
		}
		boolean hover = isHovered(mx, my);
		c.roundRect(x, y, width, height, 4, listening ? ColorUtil.withAlpha(theme().accent.argb(), 120) : hover ? theme().surfaceHover.argb() : theme().surface.argb());
		String text = c.ellipsize(c.defaultFont(), c.defaultFontSize(), listening ? "Press a key…" : getter.get().displayName(), width - 6);
		c.text(text, x + (width - c.textWidth(text)) / 2, y + (height - c.textHeight()) / 2, theme().text.argb());
		offerTooltip(mx, my);
	}

	/** The key's name, and enough for "Press a key…" not to lose too much. */
	@Override
	public float minWidth(Canvas canvas) {
		return Math.max(44, canvas.textWidth(getter.get().displayName()) + 8);
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (listening) {
			if (button == InputConstants.MOUSE_BUTTON_MIDDLE || button >= InputConstants.MOUSE_BUTTON_4) {
				setter.accept(new Keybind(button, true, Keybind.heldModifiers()));
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
		if (button == InputConstants.MOUSE_BUTTON_LEFT) {
			listening = true;
			focus();
		} else if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
			setter.accept(Keybind.NONE);
		}
		return true;
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
			case InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT -> InputConstants.MOD_SHIFT;
			case InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL -> InputConstants.MOD_CONTROL;
			case InputConstants.KEY_LALT, InputConstants.KEY_RALT -> InputConstants.MOD_ALT;
			case InputConstants.KEY_LGUI, InputConstants.KEY_RGUI -> InputConstants.MOD_SUPER;
			default -> 0;
		};
	}

	@Override
	public boolean keyPressed(int key, int scancode, int mods) {
		if (!listening) return false;
		switch (key) {
			case InputConstants.KEY_ESCAPE -> {
			}
			case InputConstants.KEY_BACKSPACE, InputConstants.KEY_DELETE -> setter.accept(Keybind.NONE);
			case InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT, InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL,
				 InputConstants.KEY_LALT, InputConstants.KEY_RALT, InputConstants.KEY_LGUI, InputConstants.KEY_RGUI -> {
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
