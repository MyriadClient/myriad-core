package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Animated;
import dev.myriadclient.api.render.Bezier;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.util.ColorUtil;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Inline icon picker over {@link Icons}, opening like a dropdown: shut, it shows the icon and its name; open, a search
 * box, the icons by section in a grid that fills the width and scrolls, and a line naming the one pointed at. "Default",
 * at the top, clears the choice (an empty string), so whatever the icon is for falls back to its own. Picking one shuts
 * it; Enter in the search picks the first match.
 */
public class IconPicker extends Widget {
	/** The least width of a grid cell; cells stretch to fill the row. */
	private static final float MIN_CELL = 17;
	private static final float SECTION = 11, DEFAULT_ROW = 12, FOOTER = 11, PAD = 3;
	/** How tall the grid gets before it scrolls. */
	private static final float MAX_GRID = 108;

	private final Supplier<String> getter;
	private final Consumer<String> setter;
	private Supplier<String> fallback = () -> "";
	private final Animated open = new Animated(0);
	private final TextField search;
	private String query = "";
	private List<Icons.Icon> shown = Icons.all();
	private boolean expanded;
	private float scroll, inset;
	private String name;
	/** The sections to show, for the search as it is. */
	private List<Group> groups = groups(Icons.all(), true);
	/** What's pointed at this frame, named on the footer. */
	private Icons.Icon pointed;
	private boolean pointedDefault;

	public IconPicker(Supplier<String> getter, Consumer<String> setter) {
		this.getter = getter;
		this.setter = setter;
		this.search = new TextField(() -> query).search().placeholder("Search icons…").onChange(s -> {
			query = s;
			shown = Icons.search(s);
			groups = groups(shown, s.isBlank());
			scroll = 0;
		}).onSubmit(s -> {
			if (!shown.isEmpty()) pick(shown.getFirst().glyph());
		});
	}

	/** The icon shown for Default: what it's for has without a choice. */
	@ApiStatus.AvailableSince("1.4.0")
	public IconPicker fallback(Supplier<String> fallback) {
		this.fallback = fallback;
		return this;
	}

	/** Beside a setting's name: the shut header starts this far in, the open list keeps the whole width. */
	void headerInset(float inset) {
		this.inset = inset;
	}

	/** Its own name at the left, in a column {@code inset} wide, as a setting's row would show it. */
	@ApiStatus.AvailableSince("1.4.0")
	public IconPicker named(String name, float inset) {
		this.name = name;
		this.inset = inset;
		return this;
	}

	@Override
	void attach(WidgetRoot root, Widget parent) {
		super.attach(root, parent);
		search.attach(root, this);
	}

	private void pick(String glyph) {
		setter.accept(glyph);
		expanded = false;
		search.releaseFocus();
	}

	@Override
	public float minWidth(Canvas c) {
		return 44;
	}

	// ---- the grid's lines, top to bottom -------------------------------------------------------------------------------

	private int columns() {
		return Math.max(1, (int) ((width - PAD * 2 - 3) / MIN_CELL));
	}

	private float cell() {
		return (width - PAD * 2 - 3) / columns();
	}

	/** Each section's name and icons, for a blank search; just the matches for a search. */
	private static List<Group> groups(List<Icons.Icon> icons, boolean bySection) {
		List<Group> out = new ArrayList<>();
		if (!bySection) {
			if (!icons.isEmpty()) out.add(new Group(null, icons));
			return out;
		}
		String section = null;
		List<Icons.Icon> current = null;
		for (Icons.Icon i : icons) {
			if (!i.section().equals(section)) {
				section = i.section();
				current = new ArrayList<>();
				out.add(new Group(section, current));
			}
			current.add(i);
		}
		return out;
	}

	private record Group(String section, List<Icons.Icon> icons) {
	}

	/** The height of all that scrolls. */
	private float contentHeight() {
		float h = query.isBlank() ? DEFAULT_ROW + 2 : 0;
		int cols = columns();
		float cell = cell();
		for (Group g : groups) {
			if (g.section != null) h += SECTION;
			h += ((g.icons.size() + cols - 1) / cols) * cell;
		}
		return Math.max(h, query.isBlank() ? 0 : cell);
	}

	private float viewHeight() {
		return Math.min(contentHeight(), MAX_GRID);
	}

	private float gridTop() {
		return y + ROW + PAD + ROW + PAD;
	}

	private float bodyHeight() {
		return PAD + ROW + PAD + viewHeight() + FOOTER + PAD;
	}

	@Override
	protected float measure(Canvas canvas, float width) {
		open.animateTo(expanded ? 1 : 0, 180, Bezier.EASE_OUT_QUINT);
		if (!expanded && open.get() <= 0) return ROW;
		search.layout(canvas, x + PAD, y + ROW + PAD, width - PAD * 2);
		return ROW + bodyHeight() * open.get();
	}

	// ---- drawing -------------------------------------------------------------------------------------------------------

	@Override
	public void render(Canvas c, float mx, float my) {
		String value = getter.get() == null ? "" : getter.get();
		float hx = x + inset, hw = width - inset;
		boolean hover = mx >= hx && my >= y && mx < x + width && my < y + ROW;
		int header = hover ? theme().surfaceHover.argb() : theme().surface.argb();
		if (height > ROW + 0.5f) {
			// Open: one block with its header when that has the whole width, or a block of its own under it.
			if (inset > 0) c.roundRect(hx, y, hw, ROW, 4, header);
			else c.roundRect(x, y, width, ROW, 4, 4, 0, 0, header);
			float top = inset > 0 ? 4 : 0;
			c.roundRect(x, y + ROW, width, height - ROW, top, top, 4, 4, theme().surface.argb());
		} else {
			c.roundRect(hx, y, hw, ROW, 4, header);
		}
		if (name != null) c.text(c.ellipsize(c.defaultFont(), c.defaultFontSize(), name, inset - 2), x, y + (ROW - c.textHeight()) / 2, theme().textDim.argb());
		boolean isDefault = value.isEmpty();
		Icons.Icon known = isDefault ? null : Icons.of(value);
		String glyph = isDefault ? fallback.get() : value;
		if (glyph != null && !glyph.isEmpty()) icon(c, glyph, hx + 2, y, ROW, isDefault ? theme().textDim.argb() : theme().accent.argb());
		String label = isDefault ? "Default" : known != null ? known.name() : "Custom";
		c.text(c.ellipsize(c.defaultFont(), c.defaultFontSize(), label, hw - ROW - 16), hx + ROW + 3, y + (ROW - c.textHeight()) / 2, isDefault ? theme().textDim.argb() : theme().text.argb());
		c.text(expanded ? "" : "", x + width - 10, y + (ROW - c.textHeight()) / 2, theme().textDim.argb());
		offerTooltip(mx, my);

		float t = open.get();
		if (t <= 0.01f) return;
		c.push();
		c.clip(x, y + ROW, width, height - ROW);
		c.alpha(t);
		search.render(c, mx, my);
		float gy = gridTop(), view = viewHeight();
		pointed = null;
		pointedDefault = false;
		c.push();
		c.clip(x, gy, width, view);
		grid(c, value, gy - scroll, gy, view, mx, my);
		c.pop();
		float max = contentHeight() - view;
		if (max > 0) {
			float barH = Math.max(10, view * view / contentHeight());
			c.roundRect(x + width - 3, gy + (view - barH) * (scroll / max), 2, barH, 1, ColorUtil.withAlpha(theme().text.argb(), 70));
		}
		footer(c, known, isDefault, gy + view);
		c.pop();
	}

	private void grid(Canvas c, String value, float top, float viewTop, float view, float mx, float my) {
		float ly = top;
		boolean inView = my >= viewTop && my < viewTop + view && mx >= x && mx < x + width;
		if (query.isBlank()) {
			boolean over = inView && my >= ly && my < ly + DEFAULT_ROW;
			boolean picked = value.isEmpty();
			float rw = width - PAD * 2 - 3;
			if (picked) c.roundRect(x + PAD, ly, rw, DEFAULT_ROW, 3, ColorUtil.withAlpha(theme().accent.argb(), 70));
			else if (over) c.roundRect(x + PAD, ly, rw, DEFAULT_ROW, 3, theme().surfaceHover.argb());
			String glyph = fallback.get();
			if (glyph != null && !glyph.isEmpty()) icon(c, glyph, x + PAD, ly, DEFAULT_ROW, theme().textDim.argb());
			c.text("Default", x + PAD + DEFAULT_ROW + 2, ly + (DEFAULT_ROW - c.textHeight()) / 2, picked ? theme().text.argb() : theme().textDim.argb());
			if (over) pointedDefault = true;
			ly += DEFAULT_ROW + 2;
		}
		int cols = columns();
		float cell = cell();
		float fs = c.defaultFontSize() * 0.8f;
		for (Group g : groups) {
			if (g.section != null) {
				c.text(FontFamily.SANS_BOLD, fs, g.section, x + PAD + 1, ly + (SECTION - c.textHeight(FontFamily.SANS_BOLD, fs)) / 2, theme().textDim.argb());
				ly += SECTION;
			}
			for (int i = 0; i < g.icons.size(); i++) {
				float cx = x + PAD + (i % cols) * cell, cy = ly + (i / cols) * cell;
				if (cy + cell < viewTop || cy > viewTop + view) continue;
				Icons.Icon icon = g.icons.get(i);
				boolean over = inView && mx >= cx && my >= cy && mx < cx + cell && my < cy + cell;
				boolean picked = icon.glyph().equals(value);
				if (picked) c.roundRect(cx + 0.5f, cy + 0.5f, cell - 1, cell - 1, 3, ColorUtil.withAlpha(theme().accent.argb(), 90));
				else if (over) c.roundRect(cx + 0.5f, cy + 0.5f, cell - 1, cell - 1, 3, theme().surfaceHover.argb());
				icon(c, icon.glyph(), cx, cy, cell, picked ? theme().accent.argb() : over ? theme().text.argb() : ColorUtil.lerp(theme().textDim.argb(), theme().text.argb(), 0.5f));
				if (over) pointed = icon;
			}
			ly += ((g.icons.size() + cols - 1) / cols) * cell;
		}
		if (!query.isBlank() && shown.isEmpty()) {
			c.text("No icon matches.", x + PAD + 2, top + (cell - c.textHeight()) / 2, theme().textDim.argb());
		}
	}

	/** Names what's pointed at, or else what's picked. */
	private void footer(Canvas c, Icons.Icon known, boolean isDefault, float fy) {
		Icons.Icon named = pointed != null ? pointed : pointedDefault ? null : known;
		String text = named != null ? named.name() + " · " + named.section() : pointedDefault || isDefault ? "Default" : "Custom";
		float s = c.defaultFontSize() * 0.8f;
		c.text(FontFamily.SANS, s, c.ellipsize(FontFamily.SANS, s, text, width - PAD * 2 - 2), x + PAD + 1, fy + (FOOTER - c.textHeight(FontFamily.SANS, s)) / 2,
			pointed != null || pointedDefault ? theme().text.argb() : theme().textDim.argb());
	}

	/** {@code glyph} centred in a {@code box}-sized square at (bx, by). */
	private static void icon(Canvas c, String glyph, float bx, float by, float box, int color) {
		float s = c.defaultFontSize() * 1.05f;
		c.text(FontFamily.MONO, s, glyph, bx + (box - c.textWidth(FontFamily.MONO, s, glyph)) / 2, by + (box - c.textHeight(FontFamily.MONO, s)) / 2, color);
	}

	// ---- input ---------------------------------------------------------------------------------------------------------

	@Override
	public boolean isNavigable() {
		return true;
	}

	@Override
	public float navHeight() {
		return ROW;
	}

	@Override
	public void activate() {
		expanded = !expanded;
		if (expanded) search.requestFocus();
		else search.releaseFocus();
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (!isHovered(mx, my)) return false;
		if (my < y + ROW) {
			// Beside a setting's name, the name's side is the row's.
			if (mx < x + inset) return false;
			activate();
			return true;
		}
		if (!expanded) return false;
		if (search.mouseClicked(mx, my, button)) return true;
		float gy = gridTop(), view = viewHeight();
		if (my < gy || my >= gy + view) return true;
		float ly = gy - scroll;
		if (query.isBlank()) {
			if (my >= ly && my < ly + DEFAULT_ROW) {
				pick("");
				return true;
			}
			ly += DEFAULT_ROW + 2;
		}
		int cols = columns();
		float cell = cell();
		for (Group g : groups) {
			if (g.section != null) ly += SECTION;
			int rows = (g.icons.size() + cols - 1) / cols;
			if (my >= ly && my < ly + rows * cell) {
				int col = (int) Math.floor((mx - x - PAD) / cell), row = (int) Math.floor((my - ly) / cell);
				int i = row * cols + col;
				if (col >= 0 && col < cols && i < g.icons.size()) pick(g.icons.get(i).glyph());
				return true;
			}
			ly += rows * cell;
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(float mx, float my, float amount) {
		if (!expanded || !isHovered(mx, my) || my < gridTop()) return false;
		float max = Math.max(0, contentHeight() - viewHeight());
		float next = Math.clamp(scroll - amount * cell(), 0, max);
		if (next == scroll) return false;
		scroll = next;
		return true;
	}
}
