package dev.myriadclient.api.ui;

import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.ui.widget.ScrollView;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.ui.widget.WidgetRoot;

/**
 * A panel whose content is a scrolling column of widgets. Implement {@link #build} to add widgets; call
 * {@link #rebuild} when the structure (not just values) changes.
 */
public abstract class WidgetPanel extends Panel {
	private WidgetRoot root;
	private ScrollView scroll;
	private final float padding;

	protected WidgetPanel() {
		this(6);
	}

	protected WidgetPanel(float padding) {
		this.padding = padding;
	}

	protected abstract void build(VBox content);

	public void rebuild() {
		float keep = scroll == null ? 0 : scroll.scroll();
		VBox content = new VBox(4, padding);
		build(content);
		scroll = new ScrollView(content, Float.MAX_VALUE);
		scroll.setScroll(keep);
		root = new WidgetRoot(scroll);
		root.setScroller(scroll);
	}

	private WidgetRoot root() {
		if (root == null) rebuild();
		return root;
	}

	@Override
	public void render(Canvas canvas, float width, float height, float mouseX, float mouseY) {
		WidgetRoot r = root();
		scroll.setViewportHeight(height);
		r.render(canvas, width, height, mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		return root().mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseReleased(float mx, float my, int button) {
		return root().mouseReleased(mx, my, button);
	}

	@Override
	public boolean mouseDragged(float mx, float my, int button, float dx, float dy) {
		return root().mouseDragged(mx, my, button, dx, dy);
	}

	@Override
	public boolean mouseScrolled(float mx, float my, float amount) {
		return root().mouseScrolled(mx, my, amount);
	}

	@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		return root().keyPressed(key, scancode, modifiers);
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		return root().charTyped(chr, modifiers);
	}

	/** True while typing in a text field or binding a key, so desktop shortcuts and Escape go to the widget. */
	@Override
	public boolean capturesKeyboard() {
		return root != null && root.focused() != null;
	}

	@Override
	public String tooltip() {
		return root == null ? null : root.tooltip();
	}

	protected WidgetRoot widgetRoot() {
		return root();
	}
}
