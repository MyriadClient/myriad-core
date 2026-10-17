package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Canvas;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays children out left to right. Each child has either a fixed width or a weight; weighted children share the
 * remaining space. Height is the tallest child.
 */
public class HBox extends Container {
	private final float spacing;
	private final List<Float> sizes = new ArrayList<>();

	public HBox(float spacing) {
		this.spacing = spacing;
	}

	public HBox() {
		this(4);
	}

	/** Adds a child sharing leftover space by {@code weight}. */
	public <W extends Widget> W addWeighted(W widget, float weight) {
		sizes.add(-weight);
		return super.add(widget);
	}

	/** Adds a child with a fixed width. */
	public <W extends Widget> W addFixed(W widget, float width) {
		sizes.add(width);
		return super.add(widget);
	}

	@Override
	public <W extends Widget> W add(W widget) {
		return addWeighted(widget, 1);
	}

	@Override
	protected float measure(Canvas canvas, float width) {
		float fixed = 0, weights = 0;
		int count = 0;
		for (int i = 0; i < children.size(); i++) {
			if (!children.get(i).isVisible()) continue;
			float s = sizes.get(i);
			if (s >= 0) fixed += s;
			else weights += -s;
			count++;
		}
		float free = Math.max(0, width - fixed - spacing * Math.max(0, count - 1));
		float cx = x, h = 0;
		for (int i = 0; i < children.size(); i++) {
			Widget w = children.get(i);
			if (!w.isVisible()) continue;
			float s = sizes.get(i);
			float cw = s >= 0 ? s : free * (-s / weights);
			h = Math.max(h, w.layout(canvas, cx, y, cw));
			cx += cw + spacing;
		}
		return h;
	}
}
