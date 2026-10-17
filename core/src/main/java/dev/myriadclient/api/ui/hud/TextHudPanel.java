package dev.myriadclient.api.ui.hud;

import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.ui.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * A HUD element made of lines of "label value" text. Implement {@link #lines} and you get sizing, right-alignment
 * when placed on the right of the screen, and the standard {@link HudStyle} colour options.
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
	protected final HudStyle style;
	private final Lines lines = new Lines();

	protected TextHudPanel(String title, String icon, HudStyle.Mode defaultMode) {
		super(title, icon);
		this.style = new HudStyle(settings, defaultMode, true);
	}

	/** Called every frame: add this frame's lines. Use {@link #preview()} to show sample values with no world. */
	protected abstract void lines(Lines out);

	/** Lines of alternating label and value parts. Labels use the label colour; values use the style's colour. */
	public static final class Lines {
		private final List<String[]> rows = new ArrayList<>();

		/** A "label value" line. */
		public Lines add(String label, String value) {
			rows.add(new String[]{label + " ", value});
			return this;
		}

		/** A line of alternating label, value, label, value… parts, drawn without extra spacing. */
		public Lines parts(String... labelValuePairs) {
			rows.add(labelValuePairs.clone());
			return this;
		}

		/** A line that's only a value. */
		public Lines value(String value) {
			rows.add(new String[]{"", value});
			return this;
		}

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
