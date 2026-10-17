package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.api.util.Keybind;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Click, then press a key or mouse button (middle/side) to bind. Escape cancels, Backspace/Delete unbinds. */
public class KeybindButton extends Widget {
	private final Supplier<Keybind> getter;
	private final Consumer<Keybind> setter;
	private boolean listening;

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
				setter.accept(Keybind.mouse(button));
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
		blur();
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
				// Wait for a non-modifier key; held modifiers are recorded with it.
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
