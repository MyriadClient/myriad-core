package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.EmptyState;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Heading;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.Note;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.ui.widget.Widget;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.impl.event.MyriadEventBus;
import dev.myriadclient.impl.event.Profiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntSupplier;

/**
 * Where the time goes: every module, service, listener and running job with the milliseconds it spent in tick, render
 * and other handlers over the last second, and hints where the numbers point at one of core's tools. The totals sit on
 * top, each owner's bar shows its share against the busiest; wide, a row is one line of columns, narrow, its numbers go
 * under its name. Timing is only on while this window is open (or {@code .profiler on}).
 */
public final class ProfilerPanel extends WidgetPanel {
	private static final int ROWS = 25;
	/** From this wide the numbers stand in columns beside the name. */
	private static final float COLUMNS_AT = 185;
	private static final float COLUMN = 30;
	private int ticks;
	private boolean ownsProfiler;
	/** Whether the rows laid out last had room for columns: their heading shows only then. */
	private boolean columns;
	private String subtitle;

	@Override
	public String title() {
		return "Profiler";
	}

	/** The last second's total. */
	@Override
	public String subtitle() {
		return subtitle;
	}

	@Override
	public String icon() {
		return "";
	}

	@Override
	public void onOpen() {
		MyriadEventBus bus = (MyriadEventBus) Myriad.events();
		if (bus.profiler() == null) {
			bus.setProfiler(new Profiler());
			ownsProfiler = true;
		}
	}

	@Override
	public void onClose() {
		if (ownsProfiler) ((MyriadEventBus) Myriad.events()).setProfiler(null);
	}

	@Override
	public void tick() {
		if (++ticks % 20 == 0) rebuild();
	}

	private static int tickColor() {
		return Myriad.ui().theme().accent.argb();
	}

	private static int renderColor() {
		return Myriad.ui().theme().blue.argb();
	}

	private static int otherColor() {
		return Myriad.ui().theme().magenta.argb();
	}

	@Override
	protected void build(VBox content) {
		Profiler profiler = ((MyriadEventBus) Myriad.events()).profiler();
		if (profiler == null) {
			content.add(new EmptyState(Icons.BAR_CHART, "Not profiling", "Turn it on with .profiler on."));
			subtitle = null;
			return;
		}
		List<Profiler.Entry> entries = profiler.snapshot();
		if (entries.stream().allMatch(e -> e.calls() == 0)) {
			// The window just opened: a second hasn't gone by yet.
			content.add(new EmptyState(Icons.TACHOMETER, "Measuring…", ""));
			subtitle = null;
			return;
		}
		double tick = 0, render = 0, other = 0, busiest = 0;
		List<Profiler.Entry> hinted = new ArrayList<>();
		for (Profiler.Entry e : entries) {
			if (!e.isJob()) {
				tick += e.tickMs();
				render += e.renderMs();
				other += e.otherMs();
			}
			busiest = Math.max(busiest, e.totalMs());
			if (e.hint() != null) hinted.add(e);
		}
		subtitle = String.format(Locale.ROOT, "%.2f ms/s", tick + render + other);

		HBox totals = new HBox(3);
		totals.add(new Stat("Tick", tick, ProfilerPanel::tickColor)).tooltip("Tick handlers, against the game's 50 ms tick.");
		totals.add(new Stat("Render", render, ProfilerPanel::renderColor)).tooltip("Render handlers, against the frame.");
		totals.add(new Stat("Other", other, ProfilerPanel::otherColor)).tooltip("Every other event's handlers.");
		content.add(totals);

		for (Profiler.Entry e : hinted) content.add(new Note(e.name() + ": " + e.hint()).warn().icon(Icons.WARNING));

		int shown = Math.min(entries.size(), ROWS);
		content.add(new Heading("By owner").hint(() -> shown == entries.size() ? String.valueOf(shown) : shown + " of " + entries.size()));
		final double most = busiest;
		VBox table = new VBox(1, 0) {
			@Override
			protected float measure(Canvas c, float width) {
				columns = width >= COLUMNS_AT;
				return super.measure(c, width);
			}
		};
		table.add(new ColumnHeads()).visible(() -> columns);
		for (Profiler.Entry e : entries.subList(0, shown)) table.add(new OwnerRow(e, most));
		content.add(table);
		content.add(new Label("Milliseconds per second in event handlers, by who runs them.").dim());
	}

	private static String ms(double v) {
		return v < 0.005 ? "-" : String.format(Locale.ROOT, "%.2f", v);
	}

	/** A number right-aligned in a column ending at {@code right}. */
	private static void number(Canvas c, String text, float right, float ty, float size, int color) {
		c.text(FontFamily.MONO, size, text, right - c.textWidth(FontFamily.MONO, size, text), ty, color);
	}

	/** A total on a tile, with the colour its part of the bars is drawn in. */
	private static final class Stat extends Widget {
		private final String name;
		private final String value;
		private final IntSupplier color;

		Stat(String name, double value, IntSupplier color) {
			this.name = name;
			this.value = ms(value);
			this.color = color;
		}

		@Override
		public float minWidth(Canvas c) {
			return 34;
		}

		@Override
		protected float measure(Canvas c, float width) {
			return c.textHeight() + c.textHeight(FontFamily.SANS, c.defaultFontSize() * 0.8f) + 8;
		}

		@Override
		public void render(Canvas c, float mx, float my) {
			c.roundRect(x, y, width, height, 4, theme().surface.argb());
			c.roundRect(x, y + 3, 2, height - 6, 1, color.getAsInt());
			float s = c.defaultFontSize() * 0.8f;
			c.text(FontFamily.SANS, s, c.ellipsize(FontFamily.SANS, s, name, width - 9), x + 6, y + 3, theme().textDim.argb());
			c.text(FontFamily.MONO, c.defaultFontSize(), c.ellipsize(FontFamily.MONO, c.defaultFontSize(), value, width - 8), x + 6, y + 4 + c.textHeight(FontFamily.SANS, s), theme().text.argb());
			offerTooltip(mx, my);
		}
	}

	/** The columns' names, over the rows. */
	private static final class ColumnHeads extends Widget {
		@Override
		protected float measure(Canvas c, float width) {
			return c.textHeight(FontFamily.SANS, c.defaultFontSize() * 0.8f) + 1;
		}

		@Override
		public void render(Canvas c, float mx, float my) {
			float s = c.defaultFontSize() * 0.8f, right = x + width - 2;
			String[] heads = {"calls", "other", "render", "tick"};
			for (String h : heads) {
				c.text(FontFamily.SANS, s, h, right - c.textWidth(FontFamily.SANS, s, h), y, theme().textDim.argb());
				right -= COLUMN + 2;
			}
		}
	}

	/**
	 * One owner: its name and numbers, and under them a bar of its share against the busiest, split by kind. Its numbers are
	 * taken when it's made: the entry moves on to each new second, and these should agree with the totals and the order.
	 */
	private final class OwnerRow extends Widget {
		private final String name, parts;
		private final double tick, render, other, total, busiest;
		private final long calls;

		OwnerRow(Profiler.Entry e, double busiest) {
			this.name = e.name();
			this.tick = e.tickMs();
			this.render = e.renderMs();
			this.other = e.otherMs();
			this.total = e.totalMs();
			this.calls = e.calls();
			this.busiest = busiest;
			List<String> list = new ArrayList<>();
			if (tick >= 0.005) list.add("tick " + ms(tick));
			if (render >= 0.005) list.add("render " + ms(render));
			if (other >= 0.005) list.add("other " + ms(other));
			list.add(calls + "×");
			this.parts = String.join(" · ", list);
			String hint = e.hint();
			// Core's own listeners have their class for an addon: nothing their name doesn't say.
			String from = e.addon().contains(".") ? "" : AddonNames.of(e.addon()) + " · ";
			this.tooltip = name + "\n" + from + parts + (hint == null ? "" : "\n" + hint);
		}

		private float small(Canvas c) {
			return c.defaultFontSize() * 0.8f;
		}

		@Override
		protected float measure(Canvas c, float width) {
			float line = c.textHeight() + 1;
			float below = columns ? 0 : c.textHeight(FontFamily.MONO, small(c)) + 1;
			return line + below + 3;
		}

		@Override
		public void render(Canvas c, float mx, float my) {
			if (isHovered(mx, my)) c.roundRect(x - 2, y, width + 4, height, 3, theme().surface.argb());
			float ty = y;
			int text = theme().text.argb(), dim = theme().textDim.argb();
			float s = small(c), mono = c.defaultFontSize() * 0.9f;
			float right = x + width - 2;
			float numberY = ty + (c.textHeight() - c.textHeight(FontFamily.MONO, mono)) / 2;
			if (columns) {
				String[] cells = {String.valueOf(calls), ms(other), ms(render), ms(tick)};
				for (int i = 0; i < cells.length; i++) {
					number(c, cells[i], right, numberY, mono, i == 0 ? dim : text);
					right -= COLUMN + 2;
				}
			} else {
				number(c, ms(total), right, numberY, mono, text);
				right -= 26;
			}
			c.text(c.ellipsize(c.defaultFont(), c.defaultFontSize(), name, right - x - 2), x + 2, ty, text);
			if (!columns) {
				String line = c.ellipsize(FontFamily.SANS, s, parts, width - 4);
				c.text(FontFamily.SANS, s, line, x + 2, ty + c.textHeight() + 1, dim);
			}
			bar(c);
			offerTooltip(mx, my);
		}

		private void bar(Canvas c) {
			float by = y + height - 2, full = width - 4;
			c.rect(x + 2, by, full, 1, ColorUtil.withAlpha(theme().textDim.argb(), 30));
			if (busiest <= 0) return;
			float scale = (float) (full / busiest), bx = x + 2;
			bx = part(c, bx, by, (float) tick * scale, tickColor());
			bx = part(c, bx, by, (float) render * scale, renderColor());
			part(c, bx, by, (float) other * scale, otherColor());
		}

		private static float part(Canvas c, float bx, float by, float w, int color) {
			if (w > 0.1f) c.rect(bx, by, w, 1, color);
			return bx + w;
		}
	}
}
