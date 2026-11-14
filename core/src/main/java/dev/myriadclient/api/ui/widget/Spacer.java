package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.util.ColorUtil;

/** Empty space, optionally with a divider line across its middle. It never asks for width in a row. */
public class Spacer extends Widget {
	private final float size;
	private final boolean line;

	/**
	 * @param size its height, in UI units
	 * @param line whether a faint line runs across its middle
	 */
	public Spacer(float size, boolean line) {
		this.size = size;
		this.line = line;
	}

	/** Empty space {@code size} units tall (or, in a row, a weighted spacer pushing its neighbours apart). */
	public static Spacer gap(float size) {
		return new Spacer(size, false);
	}

	/** A faint horizontal line with a little room above and below (7 units tall). */
	public static Spacer divider() {
		return new Spacer(7, true);
	}

	@Override
	protected float measure(Canvas canvas, float width) {
		return size;
	}

	@Override
	public float minWidth(Canvas canvas) {
		return 0;
	}

	@Override
	public void render(Canvas canvas, float mx, float my) {
		if (line) canvas.rect(x, y + size / 2, width, 1, ColorUtil.withAlpha(theme().textDim.argb(), 40));
	}
}
