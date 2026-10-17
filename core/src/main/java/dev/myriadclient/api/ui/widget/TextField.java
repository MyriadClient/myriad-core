package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.util.ColorUtil;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Single-line text input. {@code onChange} fires on every edit, {@code onSubmit} on Enter. While unfocused it shows
 * {@code getter}'s value.
 */
public class TextField extends Widget {
	private final Supplier<String> getter;
	private Consumer<String> onChange = s -> {
	};
	private Consumer<String> onSubmit = s -> {
	};
	private String placeholder = "";
	private String text = "";
	private int cursor;
	private boolean selectAll;
	private float scrollX;
	private long blinkStart;

	public TextField(Supplier<String> getter) {
		this.getter = getter;
	}

	public TextField onChange(Consumer<String> onChange) {
		this.onChange = onChange;
		return this;
	}

	public TextField onSubmit(Consumer<String> onSubmit) {
		this.onSubmit = onSubmit;
		return this;
	}

	public TextField placeholder(String placeholder) {
		this.placeholder = placeholder;
		return this;
	}

	public String text() {
		return isFocused() ? text : getter.get();
	}

	/** Focuses the field programmatically (e.g. a launcher's search box). */
	public void requestFocus() {
		if (!isFocused()) {
			text = getter.get();
			cursor = text.length();
			focus();
		}
	}

	public void setText(String s) {
		text = s;
		cursor = s.length();
		onChange.accept(text);
	}

	@Override
	protected float measure(Canvas canvas, float width) {
		return ROW;
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		boolean focused = isFocused();
		boolean hover = isHovered(mx, my);
		c.roundRect(x, y, width, height, 4, hover || focused ? theme().surfaceHover.argb() : theme().surface.argb());
		if (focused) c.outline(x, y, width, height, 4, 1, ColorUtil.withAlpha(theme().accent.argb(), 180));

		String shown = focused ? text : getter.get();
		float tx = x + 4, ty = y + (height - c.textHeight()) / 2;
		c.push();
		c.clip(x + 2, y, width - 4, height);
		if (shown.isEmpty() && !focused) {
			c.text(placeholder, tx, ty, theme().textDim.argb());
		} else {
			float cursorX = c.textWidth(shown.substring(0, Math.min(cursor, shown.length())));
			if (focused) {
				float visible = width - 8;
				if (cursorX - scrollX > visible) scrollX = cursorX - visible;
				if (cursorX - scrollX < 0) scrollX = cursorX;
			} else {
				scrollX = 0;
			}
			if (focused && selectAll && !shown.isEmpty()) {
				c.rect(tx - scrollX, ty, c.textWidth(shown), c.textHeight(), ColorUtil.withAlpha(theme().accent.argb(), 90));
			}
			c.text(shown, tx - scrollX, ty, theme().text.argb());
			if (focused && ((System.nanoTime() - blinkStart) / 500_000_000L) % 2 == 0) {
				c.rect(tx - scrollX + cursorX, ty, 0.75f, c.textHeight(), theme().text.argb());
			}
		}
		c.pop();
		offerTooltip(mx, my);
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (!isHovered(mx, my)) return false;
		if (!isFocused()) {
			text = getter.get();
			focus();
		}
		if (button == 1) {
			setText("");
		}
		cursor = text.length();
		selectAll = false;
		blinkStart = System.nanoTime();
		return true;
	}

	@Override
	public boolean isNavigable() {
		return true;
	}

	@Override
	public void activate() {
		requestFocus();
	}

	@Override
	protected void onBlur() {
		selectAll = false;
	}

	@Override
	public boolean keyPressed(int key, int scancode, int mods) {
		if (!isFocused()) return false;
		blinkStart = System.nanoTime();
		boolean ctrl = (mods & GLFW.GLFW_MOD_CONTROL) != 0;
		switch (key) {
			case GLFW.GLFW_KEY_ESCAPE -> blur();
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
				onSubmit.accept(text);
				blur();
			}
			case GLFW.GLFW_KEY_BACKSPACE -> {
				if (selectAll) {
					setText("");
					selectAll = false;
				} else if (cursor > 0) {
					int from = ctrl ? wordStart(cursor) : cursor - 1;
					text = text.substring(0, from) + text.substring(cursor);
					cursor = from;
					onChange.accept(text);
				}
			}
			case GLFW.GLFW_KEY_DELETE -> {
				if (selectAll) {
					setText("");
					selectAll = false;
				} else if (cursor < text.length()) {
					text = text.substring(0, cursor) + text.substring(cursor + 1);
					onChange.accept(text);
				}
			}
			case GLFW.GLFW_KEY_LEFT -> {
				cursor = ctrl ? wordStart(cursor) : Math.max(0, cursor - 1);
				selectAll = false;
			}
			case GLFW.GLFW_KEY_RIGHT -> {
				cursor = Math.min(text.length(), cursor + 1);
				selectAll = false;
			}
			case GLFW.GLFW_KEY_HOME -> cursor = 0;
			case GLFW.GLFW_KEY_END -> cursor = text.length();
			case GLFW.GLFW_KEY_A -> {
				if (ctrl) selectAll = true;
			}
			case GLFW.GLFW_KEY_C -> {
				if (ctrl) Minecraft.getInstance().keyboardHandler.setClipboard(text);
			}
			case GLFW.GLFW_KEY_V -> {
				if (ctrl) insert(Minecraft.getInstance().keyboardHandler.getClipboard().replace("\n", " "));
			}
			default -> {
			}
		}
		// Swallow everything while focused so desktop shortcuts don't fire mid-typing.
		return true;
	}

	private int wordStart(int from) {
		int i = from;
		while (i > 0 && text.charAt(i - 1) == ' ') i--;
		while (i > 0 && text.charAt(i - 1) != ' ') i--;
		return i;
	}

	private void insert(String s) {
		if (selectAll) {
			text = "";
			cursor = 0;
			selectAll = false;
		}
		text = text.substring(0, cursor) + s + text.substring(cursor);
		cursor += s.length();
		onChange.accept(text);
	}

	@Override
	public boolean charTyped(char chr, int mods) {
		if (!isFocused() || chr < 32 || chr == 127) return false;
		insert(String.valueOf(chr));
		blinkStart = System.nanoTime();
		return true;
	}
}
