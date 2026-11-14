package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.draw.FontFamily;
import dev.myriadclient.api.util.ColorUtil;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A short message set apart from what's around it, on a tinted strip: a warning, a failure, something worth reading.
 * The text wraps; it's tinted with the accent unless told otherwise.
 */
@ApiStatus.AvailableSince("1.4")
public class Note extends Widget {
	private static final float PAD = 4;

	private final Supplier<String> text;
	private IntSupplier color = () -> theme().accent.argb();
	private @Nullable String icon;
	private List<String> lines = List.of();

	/** A note saying {@code text}. */
	public Note(String text) {
		this(() -> text);
	}

	/** A note saying what {@code text} returns, asked every frame. */
	public Note(Supplier<String> text) {
		this.text = text;
	}

	/** Its tint, an ARGB colour asked every frame (the accent by default). Returns this note. */
	public Note color(IntSupplier color) {
		this.color = color;
		return this;
	}

	/** A warning: tinted with the theme's yellow. Returns this note. */
	public Note warn() {
		return color(() -> theme().yellow.argb());
	}

	/** A failure: tinted with the theme's red. Returns this note. */
	public Note error() {
		return color(() -> theme().red.argb());
	}

	/** A glyph ({@link dev.myriadclient.api.ui.Icons}) before the text, in the tint. Returns this note. */
	public Note icon(String glyph) {
		this.icon = glyph;
		return this;
	}

	private float textLeft() {
		return icon == null ? 7 : 16;
	}

	@Override
	public float minWidth(Canvas c) {
		return 56;
	}

	@Override
	protected float measure(Canvas c, float width) {
		lines = Label.wrap(c, c.defaultFont(), c.defaultFontSize(), text.get(), Math.max(1, width - textLeft() - PAD));
		return lines.size() * (c.textHeight() + 1) + PAD * 2 - 1;
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		int tint = color.getAsInt();
		c.roundRect(x, y, width, height, 4, ColorUtil.withAlpha(tint, 26));
		c.roundRect(x, y + 2, 2, height - 4, 1, ColorUtil.withAlpha(tint, 200));
		if (icon != null) c.text(FontFamily.MONO, c.defaultFontSize(), icon, x + 6, y + PAD, tint);
		float ly = y + PAD, lh = c.textHeight() + 1;
		for (String line : lines) {
			c.text(line, x + textLeft(), ly, theme().text.argb());
			ly += lh;
		}
		offerTooltip(mx, my);
	}
}
