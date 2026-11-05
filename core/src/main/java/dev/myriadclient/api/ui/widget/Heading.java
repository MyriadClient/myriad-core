package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.util.ColorUtil;
import org.jetbrains.annotations.ApiStatus;

import java.util.function.Supplier;

/**
 * What starts a part of a window that doesn't fold: its name, a hairline running on from it, and dim text at the end
 * (how many there are, say). For a part that folds, see {@link Collapsible}.
 */
@ApiStatus.AvailableSince("1.4.0")
public class Heading extends Widget {
	private final Supplier<String> title;
	private Supplier<String> hint;

	public Heading(String title) {
		this(() -> title);
	}

	public Heading(Supplier<String> title) {
		this.title = title;
	}

	/** Dim text at the right. */
	public Heading hint(Supplier<String> hint) {
		this.hint = hint;
		return this;
	}

	@Override
	protected float measure(Canvas c, float width) {
		return ROW - 2;
	}

	@Override
	public float minWidth(Canvas c) {
		return 30;
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		float s = c.defaultFontSize() * 0.85f, cy = y + height / 2;
		float right = x + width;
		String h = hint == null ? null : hint.get();
		if (h != null && !h.isEmpty()) {
			h = c.ellipsize(FontFamily.SANS, s, h, width * 0.45f);
			float hw = c.textWidth(FontFamily.SANS, s, h);
			right -= hw;
			c.text(FontFamily.SANS, s, h, right, cy - c.textHeight(FontFamily.SANS, s) / 2, theme().textDim.argb());
			right -= 4;
		}
		String t = c.ellipsize(FontFamily.SANS_BOLD, s, title.get(), right - x);
		c.text(FontFamily.SANS_BOLD, s, t, x, cy - c.textHeight(FontFamily.SANS_BOLD, s) / 2, theme().textDim.argb());
		float after = x + c.textWidth(FontFamily.SANS_BOLD, s, t) + 4;
		if (right - after > 4) c.rect(after, cy, right - after, 1, ColorUtil.withAlpha(theme().textDim.argb(), 40));
		offerTooltip(mx, my);
	}
}
