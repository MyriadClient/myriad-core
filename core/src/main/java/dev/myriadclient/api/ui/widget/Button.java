package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.util.ColorUtil;

import java.util.function.Supplier;

public class Button extends Widget {
	private final Supplier<String> label;
	private final Runnable action;
	private boolean accent;

	public Button(String label, Runnable action) {
		this(() -> label, action);
	}

	public Button(Supplier<String> label, Runnable action) {
		this.label = label;
		this.action = action;
	}

	/** Use the accent colour as the background. */
	public Button accent() {
		this.accent = true;
		return this;
	}

	@Override
	protected float measure(Canvas canvas, float width) {
		return ROW;
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		boolean hover = isHovered(mx, my);
		int bg = accent ? ColorUtil.withAlpha(theme().accent.argb(), hover ? 200 : 150) : (hover ? theme().surfaceHover.argb() : theme().surface.argb());
		c.roundRect(x, y, width, height, 4, bg);
		String text = label.get();
		// One glyph (an icon) is drawn whole: "…" in its place says nothing.
		if (text.codePointCount(0, text.length()) > 1) text = c.ellipsize(c.defaultFont(), c.defaultFontSize(), text, width - 8);
		c.text(text, x + (width - c.textWidth(text)) / 2, y + (height - c.textHeight()) / 2, theme().text.argb());
		offerTooltip(mx, my);
	}

	@Override
	public boolean isNavigable() {
		return true;
	}

	@Override
	public void activate() {
		action.run();
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (button != 0 || !isHovered(mx, my)) return false;
		action.run();
		return true;
	}
}
