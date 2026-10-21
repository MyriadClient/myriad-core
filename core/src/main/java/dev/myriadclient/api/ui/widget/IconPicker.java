package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Animated;
import dev.myriadclient.api.render.Bezier;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.util.ColorUtil;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Inline icon picker over {@link Icons}: collapsed it shows the icon and its name; unfolded, a search box and a grid of
 * icons (scroll it with the wheel). The first cell, "Default", clears the choice (an empty string), so whatever the icon
 * is for falls back to its own.
 */
public class IconPicker extends Widget {
	private static final float CELL = 16;
	private static final int MAX_ROWS = 6;

	private final Supplier<String> getter;
	private final Consumer<String> setter;
	private final Animated open = new Animated(0);
	private final TextField search;
	private String query = "";
	private List<Icons.Icon> shown = Icons.all();
	private boolean expanded;
	/** First grid row shown. */
	private int scroll;

	public IconPicker(Supplier<String> getter, Consumer<String> setter) {
		this.getter = getter;
		this.setter = setter;
		this.search = new TextField(() -> query).placeholder("Search icons…").onChange(s -> {
			query = s;
			shown = Icons.search(s);
			scroll = 0;
		});
	}

	@Override
	void attach(WidgetRoot root, Widget parent) {
		super.attach(root, parent);
		search.attach(root, this);
	}

	private int columns() {
		return Math.max(1, (int) (width / CELL));
	}

	/** Cells: "Default" first, then the matching icons. */
	private int cells() {
		return shown.size() + 1;
	}

	private int rows() {
		return (cells() + columns() - 1) / columns();
	}

	private float gridHeight() {
		return Math.min(rows(), MAX_ROWS) * CELL;
	}

	private float bodyHeight() {
		return 4 + ROW + 4 + gridHeight() + 2;
	}

	@Override
	protected float measure(Canvas canvas, float width) {
		open.animateTo(expanded ? 1 : 0, 180, Bezier.EASE_OUT_QUINT);
		search.layout(canvas, x, y + ROW + 4, width);
		return ROW + bodyHeight() * open.get();
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		String value = getter.get();
		boolean hover = mx >= x && my >= y && mx < x + width && my < y + ROW;
		c.roundRect(x, y, width, ROW, 4, hover ? theme().surfaceHover.argb() : theme().surface.argb());
		float size = c.defaultFontSize();
		Icons.Icon known = value == null || value.isEmpty() ? null : Icons.of(value);
		if (value != null && !value.isEmpty()) glyph(c, value, x + 3, y, ROW, theme().accent.argb());
		String label = value == null || value.isEmpty() ? "Default" : known != null ? known.name() : "Custom";
		c.text(label, x + ROW + 4, y + (ROW - c.textHeight()) / 2, theme().textDim.argb());

		float t = open.get();
		if (t <= 0.01f) return;
		c.push();
		c.clip(x, y + ROW, width, height - ROW);
		c.alpha(t);
		search.render(c, mx, my);
		float gy = y + ROW + 4 + ROW + 4;
		int cols = columns();
		float gx = x + (width - cols * CELL) / 2;
		tooltip = null;
		for (int row = 0; row < MAX_ROWS; row++) {
			for (int col = 0; col < cols; col++) {
				int i = (scroll + row) * cols + col;
				if (i >= cells()) break;
				float cx = gx + col * CELL, cy = gy + row * CELL;
				String g = i == 0 ? "" : shown.get(i - 1).glyph();
				boolean over = mx >= cx && my >= cy && mx < cx + CELL && my < cy + CELL;
				boolean picked = g.equals(value == null ? "" : value);
				if (picked) c.roundRect(cx + 1, cy + 1, CELL - 2, CELL - 2, 3, ColorUtil.withAlpha(theme().accent.argb(), 90));
				else if (over) c.roundRect(cx + 1, cy + 1, CELL - 2, CELL - 2, 3, theme().surfaceHover.argb());
				if (i == 0) {
					String d = "—";
					c.text(d, cx + (CELL - c.textWidth(d)) / 2, cy + (CELL - c.textHeight()) / 2, theme().textDim.argb());
				} else {
					glyph(c, g, cx, cy, CELL, picked ? theme().accent.argb() : theme().text.argb());
				}
				if (over) tooltip = i == 0 ? "Default" : shown.get(i - 1).name();
			}
		}
		if (shown.isEmpty()) c.text("No icon matches.", gx + CELL + 4, gy + (CELL - c.textHeight()) / 2, theme().textDim.argb());
		c.pop();
		offerTooltip(mx, my);
	}

	/** {@code glyph} centred in a {@code box}-sized square at (bx, by). */
	private static void glyph(Canvas c, String glyph, float bx, float by, float box, int color) {
		float s = c.defaultFontSize() * 1.05f;
		c.text(FontFamily.MONO, s, glyph, bx + (box - c.textWidth(FontFamily.MONO, s, glyph)) / 2, by + (box - c.textHeight(FontFamily.MONO, s)) / 2, color);
	}

	@Override
	public boolean isNavigable() {
		return true;
	}

	@Override
	public void activate() {
		expanded = !expanded;
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (!isHovered(mx, my)) return false;
		if (my < y + ROW) {
			expanded = !expanded;
			if (expanded) search.requestFocus();
			return true;
		}
		if (!expanded) return false;
		if (search.mouseClicked(mx, my, button)) return true;
		float gy = y + ROW + 4 + ROW + 4;
		int cols = columns();
		float gx = x + (width - cols * CELL) / 2;
		int col = (int) Math.floor((mx - gx) / CELL), row = (int) Math.floor((my - gy) / CELL);
		if (col < 0 || col >= cols || row < 0 || row >= MAX_ROWS) return true;
		int i = (scroll + row) * cols + col;
		if (i >= cells()) return true;
		setter.accept(i == 0 ? "" : shown.get(i - 1).glyph());
		return true;
	}

	@Override
	public boolean mouseScrolled(float mx, float my, float amount) {
		if (!expanded || !isHovered(mx, my) || my < y + ROW + 4 + ROW + 4) return false;
		int max = Math.max(0, rows() - MAX_ROWS);
		int next = Math.clamp(scroll - (int) Math.signum(amount), 0, max);
		if (next == scroll) return false;
		scroll = next;
		return true;
	}
}
