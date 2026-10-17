package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Text, wrapped to the available width. */
public class Label extends Widget {
	private final Supplier<String> text;
	private IntSupplier color = () -> theme().text.argb();
	private FontFamily font;
	private float size = -1;
	private boolean centered;
	private List<String> lines = List.of();

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

	private FontFamily f(Canvas c) {
		return font != null ? font : c.defaultFont();
	}

	private float s(Canvas c) {
		return size > 0 ? size : c.defaultFontSize();
	}

	@Override
	protected float measure(Canvas canvas, float width) {
		lines = wrap(canvas, f(canvas), s(canvas), text.get(), width);
		return lines.size() * (canvas.textHeight(f(canvas), s(canvas)) + 1);
	}

	public static List<String> wrap(Canvas c, FontFamily font, float size, String text, float width) {
		List<String> out = new ArrayList<>();
		for (String paragraph : text.split("\n", -1)) {
			StringBuilder line = new StringBuilder();
			for (String word : paragraph.split(" ")) {
				String candidate = line.isEmpty() ? word : line + " " + word;
				if (c.textWidth(font, size, candidate) <= width || line.isEmpty()) {
					line.setLength(0);
					line.append(candidate);
				} else {
					out.add(line.toString());
					line.setLength(0);
					line.append(word);
				}
			}
			out.add(line.toString());
		}
		return out;
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
