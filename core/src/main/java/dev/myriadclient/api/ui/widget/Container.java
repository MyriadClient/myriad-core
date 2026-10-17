package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Canvas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A widget with children. Input goes to the visible children in order until one consumes it. */
public abstract class Container extends Widget {
	protected final List<Widget> children = new ArrayList<>();

	public <W extends Widget> W add(W widget) {
		children.add(widget);
		if (root != null) attachTree(widget, root, this);
		return widget;
	}

	public void clear() {
		children.clear();
	}

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
