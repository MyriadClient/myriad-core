package dev.myriadclient.api.ui.hud;

import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.draw.FontFamily;

import java.util.ArrayList;
import java.util.List;

/**
 * A HUD element made of lines of "label value" text. Implement {@link #lines} and you get sizing, right-alignment
 * when placed on the right of the screen, and the standard {@link HudStyle} colour options. Text is drawn in Noto Sans
 * ({@link FontFamily#SANS}) at {@link #fontSize}; labels take the style's label colour and values its value colour, a
 * gradient or rainbow running down the values.
 *
 * <pre>{@code
 * public class PingHud extends TextHudPanel {
 *     public PingHud() { super("Ping", "", HudStyle.Mode.TEXT); }
 *
 *     @Override
 *     protected void lines(Lines out) {
 *         out.add("Ping", ping() + "ms");
 *     }
 * }
 * }</pre>
 */
public abstract class TextHudPanel extends HudPanel {
	/** The element's colour options (its "Colors" settings group). */
	protected final HudStyle style;
	private final Lines lines = new Lines();

	/**
	 * @param title       its name, also its settings group's
	 * @param icon        a Nerd Font glyph for it
	 * @param defaultMode how values are coloured until the player changes it
	 */
	protected TextHudPanel(String title, String icon, HudStyle.Mode defaultMode) {
		super(title, icon);
		this.style = new HudStyle(settings, defaultMode, true);
	}

	/**
	 * Called every frame (twice: to size the element and to draw it): add this frame's lines to {@code out}, which
	 * starts empty. Use {@link #preview()} to show sample values with no world.
	 */
	protected abstract void lines(Lines out);

	/** Lines of alternating label and value parts. Labels use the label colour; values use the style's colour. */
	public static final class Lines {
		private final List<String[]> rows = new ArrayList<>();

		/** A "label value" line (a space goes between them). Returns this, to chain. */
		public Lines add(String label, String value) {
			rows.add(new String[]{label + " ", value});
			return this;
		}

		/** A line of alternating label, value, label, value… parts, drawn without extra spacing. Returns this. */
		public Lines parts(String... labelValuePairs) {
			rows.add(labelValuePairs.clone());
			return this;
		}

		/** A line that's only a value. Returns this. */
		public Lines value(String value) {
			rows.add(new String[]{"", value});
			return this;
		}

		/** How many lines there are so far. */
		public int size() {
			return rows.size();
		}

		private void clear() {
			rows.clear();
		}
	}

	private List<String[]> collect() {
		lines.clear();
		lines(lines);
		return lines.rows;
	}

	private static float width(Canvas c, float s, String[] parts) {
		float w = 0;
		for (String p : parts) w += c.textWidth(FontFamily.SANS, s, p);
		return w;
	}

	/** As wide as the widest line (at least 20 units), as tall as the lines (at least one). */
	@Override
	public Rect preferredSize(Canvas c) {
		float s = fontSize(c);
		List<String[]> rows = collect();
		float w = 20;
		for (String[] r : rows) w = Math.max(w, width(c, s, r));
		return new Rect(0, 0, w + 1, Math.max(1, rows.size()) * (c.textHeight(FontFamily.SANS, s) + 1));
	}

	@Override
	public void render(Canvas c, float w, float h, float mx, float my) {
		float s = fontSize(c);
		float lh = c.textHeight(FontFamily.SANS, s) + 1;
		boolean right = alignRight();
		List<String[]> rows = collect();
		int values = 0;
		for (String[] r : rows) values += r.length / 2;
		int index = 0;
		float y = 0;
		for (String[] r : rows) {
			float x = right ? w - width(c, s, r) : 0;
			for (int i = 0; i < r.length; i++) {
				int color = i % 2 == 0 ? style.label() : style.color(index++, values);
				x += style.text(c, FontFamily.SANS, s, r[i], x, y, color);
			}
			y += lh;
		}
	}
}
