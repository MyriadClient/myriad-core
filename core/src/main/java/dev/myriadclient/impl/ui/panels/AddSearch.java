package dev.myriadclient.impl.ui.panels;

import com.mojang.blaze3d.platform.InputConstants;
import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.draw.FontFamily;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.ScrollView;
import dev.myriadclient.api.ui.widget.TextField;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.ui.widget.Widget;
import dev.myriadclient.api.util.ColorUtil;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A search box over a list of things to put on something (a wheel's slots, a control panel's modules). Focused it lists
 * them all, typing narrows them; a click or Enter takes one and leaves the box ready for the next. What's already on
 * shows a check, and clicking that takes it off again. Clicking anywhere else shuts the list, unless there's a search
 * in the box.
 */
final class AddSearch<T> extends VBox {
	/** One thing the search found: {@code on} means it's on already. */
	record Result<T>(T value, String icon, String label, String hint, boolean on) {
	}

	private static final float ROW_H = 13;
	private static final float MAX_LIST = ROW_H * 8;

	private final Function<String, List<Result<T>>> find;
	private final Consumer<T> pick;
	private final TextField field;
	private final VBox rows = new VBox(0, 0);
	private final ScrollView list = new ScrollView(rows, MAX_LIST).gutter(0);
	private String query = "";
	private boolean refocus, stale = true, wasOpen;

	AddSearch(String placeholder, Function<String, List<Result<T>>> find, Consumer<T> pick) {
		super(2, 0);
		this.find = find;
		this.pick = pick;
		this.field = add(new TextField(() -> query).search().placeholder(placeholder)
			.onChange(s -> {
				query = s;
				stale = true;
			})
			.onSubmit(s -> {
				for (Result<T> r : find.apply(s)) {
					if (r.on) continue;
					take(r.value);
					return;
				}
			}));
		add(list).visible(this::isOpen);
	}

	private boolean isOpen() {
		return field.hasFocus() || !query.isBlank();
	}

	/** Takes it, empties the box and hands it the keyboard again, to add several in a row. */
	private void take(T value) {
		pick.accept(value);
		query = "";
		field.setText("");
		refocus = true;
		stale = true;
	}

	private void fill() {
		stale = false;
		rows.clear();
		List<Result<T>> found = find.apply(query);
		boolean first = !query.isBlank();
		for (Result<T> r : found) {
			rows.add(new Row(r, first && !r.on));
			if (!r.on) first = false;
		}
		if (found.isEmpty()) rows.add(new Label("Nothing matches.").dim());
	}

	@Override
	protected float measure(Canvas c, float width) {
		if (refocus) {
			refocus = false;
			field.requestFocus();
		}
		// Opening shows it as it is now: what's on may have changed since it was last open.
		boolean open = isOpen();
		if (open && (stale || !wasOpen)) fill();
		wasOpen = open;
		return super.measure(c, width);
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		if (list.isVisible()) c.roundRect(list.x(), list.y(), list.width(), list.height(), 4, theme().surface.argb());
		super.render(c, mx, my);
	}

	/** This sits above what it lists for, so it sees every click first: one outside it lets go of the keyboard. */
	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		boolean inside = mx >= x && my >= y && mx < x + width && my < y + height;
		if (!inside && field.hasFocus()) field.releaseFocus();
		return inside && super.mouseClicked(mx, my, button);
	}

	/** One thing found: its icon, name and kind, a check once it's on. */
	private final class Row extends Widget {
		private final Result<T> result;
		/** What Enter would take. */
		private final boolean next;

		Row(Result<T> result, boolean next) {
			this.result = result;
			this.next = next;
		}

		@Override
		protected float measure(Canvas c, float width) {
			return ROW_H;
		}

		@Override
		public void render(Canvas c, float mx, float my) {
			boolean over = isHovered(mx, my);
			if (over) c.roundRect(x + 1, y, width - 2, height, 3, theme().surfaceHover.argb());
			else if (next) c.roundRect(x + 1, y, width - 2, height, 3, ColorUtil.withAlpha(theme().accent.argb(), 35));
			float s = c.defaultFontSize();
			if (result.icon != null && !result.icon.isEmpty()) {
				c.text(FontFamily.MONO, s, result.icon, x + 3 + (9 - c.textWidth(FontFamily.MONO, s, result.icon)) / 2, y + (height - c.textHeight(FontFamily.MONO, s)) / 2, theme().textDim.argb());
			}
			float tx = x + 15, right = x + width - 5;
			String mark = result.on ? Icons.CHECK : over ? Icons.PLUS : "";
			if (!mark.isEmpty()) {
				float ms = s * 0.8f;
				right -= c.textWidth(FontFamily.MONO, ms, mark);
				c.text(FontFamily.MONO, ms, mark, right, y + (height - c.textHeight(FontFamily.MONO, ms)) / 2, result.on ? theme().accent.argb() : theme().text.argb());
				right -= 4;
			}
			// The kind goes first when there's no room for both.
			float hs = s * 0.8f, hw = c.textWidth(FontFamily.SANS, hs, result.hint), lw = c.textWidth(result.label);
			if (!result.hint.isEmpty() && tx + lw + 6 + hw <= right) {
				c.text(FontFamily.SANS, hs, result.hint, right - hw, y + (height - c.textHeight(FontFamily.SANS, hs)) / 2, theme().textDim.argb());
				right -= hw + 6;
			}
			c.text(c.ellipsize(c.defaultFont(), s, result.label, right - tx), tx, y + (height - c.textHeight()) / 2, theme().text.argb());
		}

		@Override
		public boolean mouseClicked(float mx, float my, int button) {
			if (button != InputConstants.MOUSE_BUTTON_LEFT || !isHovered(mx, my)) return false;
			take(result.value);
			return true;
		}
	}
}
