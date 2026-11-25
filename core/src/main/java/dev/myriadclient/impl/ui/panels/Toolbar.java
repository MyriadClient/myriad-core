package dev.myriadclient.impl.ui.panels;

import com.mojang.blaze3d.platform.InputConstants;
import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.draw.FontFamily;
import dev.myriadclient.api.ui.widget.Widget;
import dev.myriadclient.api.util.ColorUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A row of small buttons, each as wide as its icon and label: the actions on a list (a theme's New, Duplicate, Delete;
 * an addon's links) without a grid of full-width buttons. Plain until hovered; a group added after {@link #gap()} sits at
 * the right end. In a row too narrow for the labels they go, icons only, each named in its tooltip.
 */
final class Toolbar extends Widget {
	private static final float PAD = 5, ICON_GAP = 4, SPACING = 3;

	private record Item(String icon, String label, @Nullable String tip, Runnable action, boolean danger, boolean accent,
						Supplier<Boolean> shown, boolean right) {
	}

	private final List<Item> items = new ArrayList<>();
	private boolean right;
	/** Where each item was drawn last (x, width), for clicks; NaN for hidden ones. */
	private float[] placed = new float[0];
	private boolean labels = true;

	Toolbar add(String icon, String label, @Nullable String tip, Runnable action) {
		return add(icon, label, tip, action, false, false, () -> true);
	}

	Toolbar add(String icon, String label, @Nullable String tip, Runnable action, boolean danger, boolean accent, Supplier<Boolean> shown) {
		items.add(new Item(icon, label, tip, action, danger, accent, shown, right));
		return this;
	}

	/** The items added after this sit at the right end. */
	Toolbar gap() {
		right = true;
		return this;
	}

	boolean isEmpty() {
		return items.isEmpty();
	}

	private float itemWidth(Canvas c, Item i, boolean withLabel) {
		float w = c.textWidth(FontFamily.MONO, c.defaultFontSize(), i.icon());
		if (withLabel && !i.label().isEmpty()) w += ICON_GAP + c.textWidth(i.label());
		return w + PAD * 2;
	}

	@Override
	protected float measure(Canvas canvas, float width) {
		return ROW;
	}

	@Override
	public float minWidth(Canvas canvas) {
		float w = 0;
		for (Item i : items) if (i.shown().get()) w += itemWidth(canvas, i, false) + SPACING;
		return w;
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		if (placed.length != items.size()) placed = new float[items.size() * 2];
		float total = 0;
		for (Item i : items) if (i.shown().get()) total += itemWidth(c, i, true) + SPACING;
		labels = total <= width;
		float left = x, rightEdge = x + width;
		// The right group is laid out from the right end, in order.
		float rightWidth = 0;
		for (Item i : items) if (i.shown().get() && i.right()) rightWidth += itemWidth(c, i, labels) + SPACING;
		float rx = rightEdge - rightWidth + SPACING;
		tooltip = null;
		for (int n = 0; n < items.size(); n++) {
			Item i = items.get(n);
			if (!i.shown().get()) {
				placed[n * 2] = Float.NaN;
				continue;
			}
			float w = itemWidth(c, i, labels);
			float ix;
			if (i.right()) {
				ix = rx;
				rx += w + SPACING;
			} else {
				ix = left;
				left += w + SPACING;
			}
			placed[n * 2] = ix;
			placed[n * 2 + 1] = w;
			boolean hover = mx >= ix && mx < ix + w && my >= y && my < y + height;
			int fg = i.danger() && hover ? theme().red.argb() : i.accent() ? theme().accent.argb() : hover ? theme().text.argb() : theme().textDim.argb();
			int bg = hover ? (i.danger() ? ColorUtil.withAlpha(theme().red.argb(), 50) : theme().surfaceHover.argb())
				: i.accent() ? ColorUtil.withAlpha(theme().accent.argb(), 40) : 0;
			if (bg != 0) c.roundRect(ix, y, w, height, 4, bg);
			float size = c.defaultFontSize();
			float ty = y + (height - c.textHeight()) / 2;
			float tx = ix + PAD;
			c.text(FontFamily.MONO, size, i.icon(), tx, y + (height - c.textHeight(FontFamily.MONO, size)) / 2, fg);
			tx += c.textWidth(FontFamily.MONO, size, i.icon());
			if (labels && !i.label().isEmpty()) c.text(i.label(), tx + ICON_GAP, ty, fg);
			if (hover) tooltip = labels ? i.tip() : i.tip() == null ? i.label() : i.label() + "\n" + i.tip();
		}
		offerTooltip(mx, my);
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (button != InputConstants.MOUSE_BUTTON_LEFT || !isHovered(mx, my)) return false;
		for (int n = 0; n < items.size(); n++) {
			float ix = placed.length > n * 2 ? placed[n * 2] : Float.NaN;
			if (Float.isNaN(ix) || mx < ix || mx >= ix + placed[n * 2 + 1]) continue;
			items.get(n).action().run();
			return true;
		}
		return false;
	}
}
