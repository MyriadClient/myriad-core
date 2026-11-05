package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Text, wrapped to the available width (or cut short with "…", see {@link #oneLine}). */
public class Label extends Widget {
	/** How narrow a long text can wrap in a tight row, and how wide its longest word can ask for. */
	private static final float MIN_WRAPPED = 44, MAX_WORD = 70;

	private final Supplier<String> text;
	private IntSupplier color = () -> theme().text.argb();
	private FontFamily font;
	private float size = -1;
	private boolean centered, oneLine;
	private List<String> lines = List.of();
	/** What the tooltip showed before this cut itself short, to give it back once the text fits. */
	private String ownTooltip;
	private boolean cut;

	public Label(String text) {
		this(() -> text);
	}

	public Label(Supplier<String> text) {
		this.text = text;
	}

	public Label color(IntSupplier color) {
		this.color = color;
		return this;
	}

	public Label dim() {
		return color(() -> theme().textDim.argb());
	}

	public Label font(FontFamily font, float size) {
		this.font = font;
		this.size = size;
		return this;
	}

	public Label bold() {
		this.font = FontFamily.SANS_BOLD;
		return this;
	}

	public Label centered() {
		this.centered = true;
		return this;
	}

	/** Keeps it to one line, cut short with "…" when it doesn't fit; the whole text shows as its tooltip then. */
	@ApiStatus.AvailableSince("1.4.0")
	public Label oneLine() {
		this.oneLine = true;
		return this;
	}

	private FontFamily f(Canvas c) {
		return font != null ? font : c.defaultFont();
	}

	private float s(Canvas c) {
		return size > 0 ? size : c.defaultFontSize();
	}

	@Override
	protected float measure(Canvas canvas, float width) {
		String t = text.get();
		if (oneLine) {
			String shown = canvas.ellipsize(f(canvas), s(canvas), t, width);
			lines = List.of(shown);
			boolean nowCut = !shown.equals(t);
			if (nowCut && !cut) ownTooltip = tooltip;
			if (nowCut) tooltip = t;
			else if (cut) tooltip = ownTooltip;
			cut = nowCut;
		} else {
			lines = wrap(canvas, f(canvas), s(canvas), t, width);
		}
		return lines.size() * (canvas.textHeight(f(canvas), s(canvas)) + 1);
	}

	/** Enough for a short text whole; a long one can wrap, but not so narrow that it breaks inside a word. */
	@Override
	public float minWidth(Canvas canvas) {
		String t = text.get();
		float whole = canvas.textWidth(f(canvas), s(canvas), t);
		if (oneLine) return (float) Math.ceil(Math.min(whole, 30)) + 1;
		float word = 0;
		if (whole > MIN_WRAPPED) for (String w : t.split("\\s+")) word = Math.max(word, canvas.textWidth(f(canvas), s(canvas), w));
		return (float) Math.ceil(Math.min(whole, Math.clamp(word, MIN_WRAPPED, MAX_WORD))) + 1;
	}

	/** {@code text} in lines at most {@code width} wide, broken at spaces; a word wider than a line is broken inside it. */
	public static List<String> wrap(Canvas c, FontFamily font, float size, String text, float width) {
		List<String> out = new ArrayList<>();
		for (String paragraph : text.split("\n", -1)) {
			StringBuilder line = new StringBuilder();
			for (String word : paragraph.split(" ")) {
				String candidate = line.isEmpty() ? word : line + " " + word;
				if (c.textWidth(font, size, candidate) <= width) {
					line.setLength(0);
					line.append(candidate);
					continue;
				}
				if (!line.isEmpty()) {
					out.add(line.toString());
					line.setLength(0);
				}
				String rest = word;
				while (rest.length() > 1 && c.textWidth(font, size, rest) > width) {
					int fit = fitting(c, font, size, rest, width);
					out.add(rest.substring(0, fit));
					rest = rest.substring(fit);
				}
				line.append(rest);
			}
			out.add(line.toString());
		}
		return out;
	}

	/** How many of {@code text}'s characters fit in {@code width}: at least one, so wrapping always gets through it. */
	private static int fitting(Canvas c, FontFamily font, float size, String text, float width) {
		int fit = 1;
		while (fit < text.length() && c.textWidth(font, size, text.substring(0, fit + 1)) <= width) fit++;
		return fit;
	}

	@Override
	public void render(Canvas canvas, float mx, float my) {
		float lh = canvas.textHeight(f(canvas), s(canvas)) + 1;
		float cy = y;
		for (String line : lines) {
			float lx = centered ? x + (width - canvas.textWidth(f(canvas), s(canvas), line)) / 2 : x;
			canvas.text(f(canvas), s(canvas), line, lx, cy, color.getAsInt());
			cy += lh;
		}
		offerTooltip(mx, my);
	}
}
