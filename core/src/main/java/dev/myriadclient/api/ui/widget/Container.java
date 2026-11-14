package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.ui.draw.Canvas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A widget with children. Input goes to the visible children in order until one consumes it (the wheel only to the
 * hovered ones), and they draw in order. Subclasses lay the children out in {@link #measure} by calling each one's
 * {@link Widget#layout}, hidden ones included (they take no room).
 */
public abstract class Container extends Widget {
	/** The children, in order; subclasses read it, and should change it only through {@link #add} and {@link #clear}. */
	protected final List<Widget> children = new ArrayList<>();

	/** Adds {@code widget} as the last child and returns it, so it can be added and kept in one line. */
	public <W extends Widget> W add(W widget) {
		children.add(widget);
		if (root != null) attachTree(widget, root, this);
		return widget;
	}

	/** Removes every child; keyboard focus on one of them (or inside one) goes too. */
	public void clear() {
		if (root != null && root.focused() != null && inside(root.focused())) root.setFocused(null);
		children.clear();
	}

	/** Whether {@code widget} is one of this container's descendants. */
	private boolean inside(Widget widget) {
		for (Widget w = widget.parent; w != null; w = w.parent) if (w == this) return true;
		return false;
	}

	/** The children, in order, as a read-only view. */
	public List<Widget> children() {
		return Collections.unmodifiableList(children);
	}

	@Override
	void attach(WidgetRoot root, Widget parent) {
		super.attach(root, parent);
		for (Widget w : children) attachTree(w, root, this);
	}

	static void attachTree(Widget w, WidgetRoot root, Widget parent) {
		w.attach(root, parent);
	}

	@Override
	public void render(Canvas canvas, float mouseX, float mouseY) {
		for (Widget w : children) if (w.isVisible()) w.render(canvas, mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		for (Widget w : children) {
			if (w.isVisible() && w.mouseClicked(mx, my, button)) {
				if (root != null && root.pressed() == null) root.setPressed(w);
				return true;
			}
		}
		return false;
	}

	@Override
	public void collectNavigable(List<Widget> out) {
		if (!isVisible()) return;
		super.collectNavigable(out);
		for (Widget w : children) w.collectNavigable(out);
	}

	@Override
	public boolean mouseScrolled(float mx, float my, float amount) {
		for (Widget w : children) if (w.isVisible() && w.isHovered(mx, my) && w.mouseScrolled(mx, my, amount)) return true;
		return false;
	}
}
