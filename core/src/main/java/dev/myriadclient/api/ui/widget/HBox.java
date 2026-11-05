package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Canvas;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays children out left to right. Each child has either a fixed width or a weight; weighted children share the
 * remaining space. Height is the tallest child.
 * <p>
 * In a narrow window it gives way rather than overflow: fixed children shrink towards their {@linkplain Widget#minWidth
 * least width} so the weighted ones keep theirs, and if that isn't enough, children wrap onto more lines, each line
 * filling the width (a name above its buttons, the buttons side by side).
 */
public class HBox extends Container {
	/** What a weighted child with no opinion of its own gets at least. */
	private static final float MIN_SHARED = 36;
	/** Above this much a weighted child, nothing needs asking how narrow it can get. */
	private static final float ROOMY = 64;

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
		float fixed = 0;
		int count = 0, shared = 0;
		for (int i = 0; i < children.size(); i++) {
			if (!children.get(i).isVisible()) continue;
			float s = sizes.get(i);
			if (s >= 0) fixed += s;
			else shared++;
			count++;
		}
		float free = width - fixed - spacing * Math.max(0, count - 1);
		if (free >= shared * ROOMY && free >= 0) return line(canvas, 0, children.size(), y, width, false, false);

		// Tight: everything on one line if the least widths fit, else as many lines as it takes.
		float min = spacing * Math.max(0, count - 1);
		for (int i = 0; i < children.size(); i++) {
			if (children.get(i).isVisible()) min += least(canvas, i);
		}
		if (min <= width) return line(canvas, 0, children.size(), y, width, false, true);
		float cy = y;
		int from = 0;
		boolean first = true;
		while (from < children.size()) {
			float used = 0;
			int to = from, onLine = 0;
			for (; to < children.size(); to++) {
				if (!children.get(to).isVisible()) continue;
				float need = least(canvas, to) + (onLine > 0 ? spacing : 0);
				if (onLine > 0 && used + need > width) break;
				used += need;
				onLine++;
			}
			if (onLine > 0) {
				if (!first) cy += spacing;
				cy += line(canvas, from, to, cy, width, true, true);
				first = false;
			}
			from = to;
		}
		return cy - y;
	}

	/** Child {@code i}'s least width. */
	private float least(Canvas canvas, int i) {
		float s = sizes.get(i), min = children.get(i).minWidth(canvas);
		if (s >= 0) return min < 0 ? s : Math.min(s, min);
		return min < 0 ? MIN_SHARED : min;
	}

	/**
	 * Lays out children {@code from} to {@code to} on one line at {@code ly}; returns its height. {@code tight}, fixed
	 * children give up width for the shared ones' least, down to their own; {@code wrapped}, a line of only fixed children
	 * stretches them to fill it.
	 */
	private float line(Canvas canvas, int from, int to, float ly, float width, boolean wrapped, boolean tight) {
		float fixed = 0, weights = 0;
		int count = 0;
		for (int i = from; i < to; i++) {
			if (!children.get(i).isVisible()) continue;
			float s = sizes.get(i);
			if (s >= 0) fixed += s;
			else weights += -s;
			count++;
		}
		float room = width - spacing * Math.max(0, count - 1);
		float forFixed = fixed, give = 0, sharedMin = 0;
		if (tight) {
			float fixedMin = 0;
			int shared = 0;
			for (int i = from; i < to; i++) {
				if (!children.get(i).isVisible()) continue;
				if (sizes.get(i) >= 0) {
					fixedMin += least(canvas, i);
				} else {
					float least = least(canvas, i);
					sharedMin += least;
					// A spacer asks for nothing.
					if (least > 0) shared++;
				}
			}
			// The shared ones get a good width where they can, not only their least: rows in a column then keep their
			// fixed children lined up, however long each row's text is.
			float forShared = Math.max(sharedMin, Math.min(shared * ROOMY, room - fixedMin));
			forFixed = Math.min(fixed, Math.max(fixedMin, room - forShared));
			give = fixed > fixedMin ? (fixed - forFixed) / (fixed - fixedMin) : 0;
		}
		float stretch = wrapped && weights == 0 && fixed > 0 ? room / fixed : 1;
		float free = Math.max(0, room - forFixed);
		float cx = x, h = 0;
		for (int i = from; i < to; i++) {
			Widget w = children.get(i);
			if (!w.isVisible()) {
				w.layout(canvas, cx, ly, 0);
				continue;
			}
			float s = sizes.get(i);
			float cw;
			// Tight, each shared child has its least and a share of the rest: a long button's text isn't cut for a short one's.
			if (s < 0) cw = tight ? least(canvas, i) + Math.max(0, free - sharedMin) * (-s / weights) : free * (-s / weights);
			else if (stretch != 1) cw = s * stretch;
			else cw = give > 0 ? s - (s - least(canvas, i)) * give : s;
			h = Math.max(h, w.layout(canvas, cx, ly, cw));
			cx += cw + spacing;
		}
		// Text beside a button or field lines up with theirs; beside a list opening under one, it stays with the header.
		float centre = Math.min(h, Widget.ROW);
		for (int i = from; i < to; i++) {
			Widget w = children.get(i);
			if (w instanceof Label && w.isVisible() && w.height() < centre) w.layout(canvas, w.x(), ly + (centre - w.height()) / 2, w.width());
		}
		return h;
	}
}
