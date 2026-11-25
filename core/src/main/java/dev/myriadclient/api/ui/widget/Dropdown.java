package dev.myriadclient.api.ui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import dev.myriadclient.api.ui.draw.Animated;
import dev.myriadclient.api.ui.draw.Bezier;
import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.draw.FontFamily;
import dev.myriadclient.api.util.ColorUtil;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Pick one of several values: click to expand the options inline (the widget grows, pushing what's below down).
 * Right-click, Enter, or h/l with the keyboard selection on it step through the values without opening it. The
 * current value is highlighted in the list.
 *
 * <pre>{@code
 * content.add(new Dropdown<>(() -> List.of(Mode.values()), mode::get, mode::set, m -> m.name().toLowerCase()));
 * }</pre>
 *
 * @param <T> the value type; values are compared with {@code equals}
 */
public class Dropdown<T> extends Widget {
	private final Supplier<List<T>> options;
	private final Supplier<T> getter;
	private final Consumer<T> setter;
	private final Function<T, String> names;
	private final Animated open = new Animated(0);
	private boolean expanded;

	/**
	 * @param options the values to choose from, asked every frame (keep it non-empty)
	 * @param getter  the current value, read every frame
	 * @param setter  called with the value picked
	 * @param names   what each value is called on screen
	 */
	public Dropdown(Supplier<List<T>> options, Supplier<T> getter, Consumer<T> setter, Function<T, String> names) {
		this.options = options;
		this.getter = getter;
		this.setter = setter;
		this.names = names;
	}

	/** An option's height, and the inset of every text from the left edge (the value and the options line up). */
	private static final float OPTION = ROW - 1, PAD = 6;
	/** A long list shows this many options at once and scrolls the rest. */
	private static final int VISIBLE = 8;
	private float scroll;

	private float listHeight() {
		return Math.min(options.get().size(), VISIBLE) * OPTION;
	}

	@Override
	protected float measure(Canvas canvas, float width) {
		open.animateTo(expanded ? 1 : 0, 160, Bezier.EASE_OUT_QUINT);
		return ROW + (listHeight() + 4) * open.get();
	}

	@Override
	public float minWidth(Canvas canvas) {
		return 44;
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		boolean hover = mx >= x && my >= y && mx < x + width && my < y + ROW;
		float t = open.get();
		boolean opened = height > ROW + 0.5f;
		field(c, x, y, width, height, ROW, hover, opened, t);
		String value = names.apply(getter.get());
		c.text(c.ellipsize(c.defaultFont(), c.defaultFontSize(), value, width - PAD - 14), x + PAD, y + (ROW - c.textHeight()) / 2, theme().text.argb());
		chevron(c, x, y, width, ROW, expanded);
		if (t > 0.01f) {
			c.push();
			c.clip(x, y + ROW, width, height - ROW);
			c.alpha(t);
			float top = y + ROW + 2, oy = top - scroll;
			T current = getter.get();
			List<T> all = options.get();
			c.clip(x, top, width, Math.max(0, Math.min(listHeight(), height - ROW - 2)));
			for (T option : all) {
				if (oy + OPTION < top || oy > top + listHeight()) {
					oy += OPTION;
					continue;
				}
				boolean h = mx >= x && my >= oy && mx < x + width && my < oy + OPTION && my >= top && my < top + listHeight();
				boolean picked = option.equals(current);
				if (h) c.roundRect(x + 2, oy, width - 4, OPTION, 3, theme().surfaceHover.argb());
				else if (picked) c.roundRect(x + 2, oy, width - 4, OPTION, 3, ColorUtil.withAlpha(theme().accent.argb(), 30));
				int color = picked ? theme().accent.argb() : h ? theme().text.argb() : theme().textDim.argb();
				float ty = oy + (OPTION - c.textHeight()) / 2;
				c.text(c.ellipsize(c.defaultFont(), c.defaultFontSize(), names.apply(option), width - PAD - 16), x + PAD, ty, color);
				oy += OPTION;
			}
			c.pop();
			float total = all.size() * OPTION;
			if (total > listHeight() + 0.5f) {
				float lh = listHeight(), bh = Math.max(10, lh * lh / total);
				float by = top + (lh - bh) * (scroll / (total - lh));
				c.roundRect(x + width - 4, by, 2, bh * t, 1, ColorUtil.withAlpha(theme().textDim.argb(), 110));
			}
		}
		offerTooltip(mx, my);
	}

	/**
	 * The look of a field that opens (a dropdown, a picker): its fill, a hairline round it that turns to the accent while
	 * it's hovered or open, and with an open list under the header, the two as one panel. {@code open} 0..1 fades the
	 * outline's accent in.
	 */
	static void field(Canvas c, float x, float y, float width, float height, float header, boolean hover, boolean opened, float open) {
		int fill = hover ? theme().surfaceHover.argb() : theme().surface.argb();
		if (opened) {
			c.roundRect(x, y, width, header, 4, 4, 0, 0, fill);
			c.roundRect(x, y + header, width, height - header, 0, 0, 4, 4, ColorUtil.withAlpha(theme().surface.argb(),
				Math.min(255, ColorUtil.alpha(theme().surface.argb()) + 40)));
			c.rect(x + 4, y + header, width - 8, 1, ColorUtil.withAlpha(theme().inactiveBorder.argb(), 140));
		} else {
			c.roundRect(x, y, width, height, 4, fill);
		}
		int rest = ColorUtil.withAlpha(theme().inactiveBorder.argb(), 150);
		int lit = ColorUtil.withAlpha(theme().accent.argb(), 170);
		int border = opened ? ColorUtil.lerp(rest, lit, Math.max(open, 0.4f)) : hover ? ColorUtil.withAlpha(theme().accent.argb(), 110) : rest;
		c.outline(x, y, width, height, 4, 1, border);
	}

	/** The open/shut arrow at a field's right end. */
	static void chevron(Canvas c, float x, float y, float width, float header, boolean open) {
		float size = c.defaultFontSize() * 0.85f;
		c.text(FontFamily.MONO, size, open ? "\uf077" : "\uf078", x + width - 11, y + (header - c.textHeight(FontFamily.MONO, size)) / 2,
			open ? theme().accent.argb() : theme().textDim.argb());
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (!isHovered(mx, my)) return false;
		if (my < y + ROW) {
			if (button == InputConstants.MOUSE_BUTTON_LEFT) {
				expanded = !expanded;
				// Opened on the current value, a few rows down from the top.
				if (expanded) {
					List<T> list = options.get();
					float max = Math.max(0, list.size() * OPTION - listHeight());
					scroll = Math.clamp((list.indexOf(getter.get()) - 3) * OPTION, 0, max);
				}
			}
			else if (button == InputConstants.MOUSE_BUTTON_RIGHT) cycle(1);
			return true;
		}
		if (expanded) {
			if (my < y + ROW + 2 || my >= y + ROW + 2 + listHeight()) return true;
			int i = (int) ((my - y - ROW - 2 + scroll) / OPTION);
			List<T> list = options.get();
			if (i >= 0 && i < list.size()) {
				setter.accept(list.get(i));
				expanded = false;
			}
			return true;
		}
		return false;
	}

	private void cycle(int dir) {
		List<T> list = options.get();
		int i = list.indexOf(getter.get());
		setter.accept(list.get(Math.floorMod(i + dir, list.size())));
	}

	@Override
	public boolean isNavigable() {
		return true;
	}

	@Override
	public void activate() {
		cycle(1);
	}

	@Override
	public boolean adjust(int direction) {
		cycle(direction);
		return true;
	}

	/**
	 * The wheel scrolls the window, never the value: scrolling down a list of settings mustn't change the ones it passes.
	 * Over an open long list, it scrolls the list.
	 */
	@Override
	public boolean mouseScrolled(float mx, float my, float amount) {
		if (!expanded || !isHovered(mx, my) || my < y + ROW) return false;
		float max = Math.max(0, options.get().size() * OPTION - listHeight());
		if (max <= 0) return false;
		float next = Math.clamp(scroll - amount * OPTION * 2, 0, max);
		if (next == scroll) return false;
		scroll = next;
		return true;
	}
}
