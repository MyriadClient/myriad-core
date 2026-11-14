package dev.myriadclient.api.ui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import dev.myriadclient.api.input.Keybind;
import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.draw.FontFamily;
import dev.myriadclient.api.util.ColorUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.InputQuirks;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Single-line text input. {@code onChange} fires on every edit, {@code onSubmit} on Enter. While unfocused it shows
 * {@code getter}'s value; clicking it (or Enter, or {@code /} for the first field in a window) starts editing a copy of
 * that value, and the field holds keyboard focus until Enter, Escape or a click elsewhere. While focused it takes every
 * key, so the UI's shortcuts don't fire. Ctrl+Left and Ctrl+Backspace go by words, Ctrl+A/C/V (Cmd on a Mac) select
 * all, copy and paste; right-click empties it.
 *
 * <pre>{@code
 * content.add(new TextField(() -> filter).onChange(s -> filter = s).search().placeholder("Filter…"));
 * }</pre>
 */
public class TextField extends Widget {
	/** The room a search box's cross takes at its right. */
	private static final float CLEAR = 11;

	private final Supplier<String> getter;
	private Consumer<String> onChange = s -> {
	};
	private Consumer<String> onSubmit = s -> {
	};
	private String placeholder = "";
	private @Nullable String icon;
	private boolean clearable;
	private String text = "";
	private int cursor;
	private boolean selectAll;
	private float scrollX;
	private long blinkStart;

	/** A field showing {@code getter}'s value while it isn't being edited (read every frame). */
	public TextField(Supplier<String> getter) {
		this.getter = getter;
	}

	/** Called with the whole text after every edit while typing (including emptying it). Returns this field. */
	public TextField onChange(Consumer<String> onChange) {
		this.onChange = onChange;
		return this;
	}

	/** Called with the text when Enter is pressed, before the field lets go of the keyboard. Returns this field. */
	public TextField onSubmit(Consumer<String> onSubmit) {
		this.onSubmit = onSubmit;
		return this;
	}

	/** Dim text shown while the field is empty and not being edited. Returns this field. */
	public TextField placeholder(String placeholder) {
		this.placeholder = placeholder;
		return this;
	}

	/** A glyph at the left of the field: a search box's magnifier. Returns this field. */
	@ApiStatus.AvailableSince("1.4")
	public TextField icon(String glyph) {
		this.icon = glyph;
		return this;
	}

	/**
	 * A search box: the magnifier at its left, and a cross at its right that empties it once it holds something.
	 * Returns this field.
	 */
	@ApiStatus.AvailableSince("1.4")
	public TextField search() {
		this.clearable = true;
		return icon(Icons.SEARCH);
	}

	/** What's typed while it's being edited, otherwise {@code getter}'s value. */
	public String text() {
		return isFocused() ? text : getter.get();
	}

	/** Whether the keyboard is typing here. */
	@ApiStatus.AvailableSince("1.4")
	public boolean hasFocus() {
		return isFocused();
	}

	/** Lets go of the keyboard, as Escape does. */
	@ApiStatus.AvailableSince("1.4")
	public void releaseFocus() {
		blur();
	}

	/** Focuses the field programmatically (e.g. a launcher's search box). */
	public void requestFocus() {
		if (!isFocused()) {
			text = getter.get();
			cursor = text.length();
			focus();
		}
	}

	/**
	 * Replaces what's being typed with {@code s}, the cursor at its end, and calls {@code onChange}. While the field
	 * isn't focused it shows {@code getter}'s value regardless.
	 */
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
	public float minWidth(Canvas canvas) {
		return 40;
	}

	private float textLeft() {
		return icon == null ? 4 : 13;
	}

	private boolean showsClear() {
		return clearable && !text().isEmpty();
	}

	private boolean overClear(float mx, float my) {
		return showsClear() && mx >= x + width - CLEAR && mx < x + width && my >= y && my < y + height;
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		boolean focused = isFocused();
		boolean hover = isHovered(mx, my);
		c.roundRect(x, y, width, height, 4, hover || focused ? theme().surfaceHover.argb() : theme().surface.argb());
		if (focused) c.outline(x, y, width, height, 4, 1, ColorUtil.withAlpha(theme().accent.argb(), 180));

		String shown = focused ? text : getter.get();
		float tx = x + textLeft(), ty = y + (height - c.textHeight()) / 2;
		float right = showsClear() ? CLEAR : 4;
		if (icon != null) {
			float s = c.defaultFontSize() * 0.85f;
			c.text(FontFamily.MONO, s, icon, x + 3.5f, y + (height - c.textHeight(FontFamily.MONO, s)) / 2, focused ? theme().accent.argb() : theme().textDim.argb());
		}
		if (showsClear()) {
			float s = c.defaultFontSize() * 0.75f;
			c.text(FontFamily.MONO, s, Icons.TIMES, x + width - CLEAR + 3, y + (height - c.textHeight(FontFamily.MONO, s)) / 2,
				overClear(mx, my) ? theme().text.argb() : theme().textDim.argb());
		}
		c.push();
		c.clip(tx - 1, y, Math.max(0, x + width - right - tx + 1), height);
		if (shown.isEmpty() && !focused) {
			c.text(c.ellipsize(c.defaultFont(), c.defaultFontSize(), placeholder, x + width - right - tx), tx, ty, theme().textDim.argb());
		} else {
			float cursorX = c.textWidth(shown.substring(0, Math.min(cursor, shown.length())));
			if (focused) {
				float visible = x + width - right - tx;
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
		if (button == InputConstants.MOUSE_BUTTON_RIGHT || overClear(mx, my)) {
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
		boolean ctrl = (mods & InputConstants.MOD_CONTROL) != 0;
		// Select all, copy and paste take Cmd too on a Mac, as Minecraft's own text fields do.
		boolean edit = ctrl || (mods & InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER) != 0;
		switch (Keybind.letter(key)) {
			case InputConstants.KEY_ESCAPE -> blur();
			case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
				onSubmit.accept(text);
				blur();
			}
			case InputConstants.KEY_BACKSPACE -> {
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
			case InputConstants.KEY_DELETE -> {
				if (selectAll) {
					setText("");
					selectAll = false;
				} else if (cursor < text.length()) {
					text = text.substring(0, cursor) + text.substring(cursor + 1);
					onChange.accept(text);
				}
			}
			case InputConstants.KEY_LEFT -> {
				cursor = ctrl ? wordStart(cursor) : Math.max(0, cursor - 1);
				selectAll = false;
			}
			case InputConstants.KEY_RIGHT -> {
				cursor = Math.min(text.length(), cursor + 1);
				selectAll = false;
			}
			case InputConstants.KEY_HOME -> cursor = 0;
			case InputConstants.KEY_END -> cursor = text.length();
			case InputConstants.KEY_A -> {
				if (edit) selectAll = true;
			}
			case InputConstants.KEY_C -> {
				if (edit) Minecraft.getInstance().keyboardHandler.setClipboard(text);
			}
			case InputConstants.KEY_V -> {
				if (edit) insert(Minecraft.getInstance().keyboardHandler.getClipboard().replace("\n", " "));
			}
			default -> {
			}
		}
		// Swallow everything while focused so UI shortcuts don't fire mid-typing.
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
