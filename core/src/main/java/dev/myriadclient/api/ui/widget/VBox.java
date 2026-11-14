package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.ui.draw.Canvas;

/**
 * Stacks children vertically, each at full width (less the padding). Hidden children take no room and no spacing.
 * Its height is the children's plus the spacing between them and the padding above and below.
 */
public class VBox extends Container {
	private final float spacing;
	private final float padding;

	/** A column with 2 units between children and no padding. */
	public VBox() {
		this(2, 0);
	}

	/**
	 * @param spacing the gap between children, in UI units
	 * @param padding the room left on every side, in UI units
	 */
	public VBox(float spacing, float padding) {
		this.spacing = spacing;
		this.padding = padding;
	}

	@Override
	protected float measure(Canvas canvas, float width) {
		float cy = y + padding;
		boolean first = true;
		for (Widget w : children) {
			if (!w.isVisible()) {
				w.layout(canvas, x + padding, cy, width - padding * 2);
				continue;
			}
			if (!first) cy += spacing;
			cy += w.layout(canvas, x + padding, cy, width - padding * 2);
			first = false;
		}
		return cy - y + padding;
	}
}
