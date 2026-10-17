package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Canvas;

/** Stacks children vertically, each at full width. */
public class VBox extends Container {
	private final float spacing;
	private final float padding;

	public VBox() {
		this(2, 0);
	}

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
