package dev.myriadclient.api.ui;

import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.widget.ScrollView;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.ui.widget.WidgetRoot;
import org.jspecify.annotations.Nullable;

/**
 * A panel whose content is a scrolling column of widgets. Implement {@link #build} to add widgets; call
 * {@link #rebuild} when the structure (not just values) changes. Widgets read live values through suppliers each
 * frame, so changing a value needs no rebuild. Input, keyboard navigation, focus and tooltips are handled by its
 * {@link WidgetRoot}.
 *
 * <pre>{@code
 * public final class NotesPanel extends WidgetPanel {
 *     private final List<String> notes = new ArrayList<>();
 *     private String draft = "";
 *
 *     @Override
 *     public String title() { return "Notes"; }
 *
 *     @Override
 *     protected void build(VBox content) {
 *         HBox add = content.add(new HBox());
 *         add.addWeighted(new TextField(() -> draft).onChange(s -> draft = s).placeholder("New note"), 1);
 *         add.addFixed(new Button("Add", () -> { notes.add(draft); draft = ""; rebuild(); }), 40);
 *         if (notes.isEmpty()) content.add(new EmptyState(Icons.PENCIL, "No notes yet", "Type one above."));
 *         for (String n : notes) content.add(new Label(n));
 *     }
 * }
 * }</pre>
 */
public abstract class WidgetPanel extends Panel {
	private @Nullable WidgetRoot root;
	private @Nullable ScrollView scroll;
	private final float padding;

	/** A panel with 6 UI units of padding around its content. */
	protected WidgetPanel() {
		this(6);
	}

	/** A panel with {@code padding} UI units around its content (the scroll bar sits in the right-hand padding). */
	protected WidgetPanel(float padding) {
		this.padding = padding;
	}

	/**
	 * Adds the panel's widgets to {@code content}, a column with 4 units between widgets and the panel's padding
	 * around them. Called before the first frame and on every {@link #rebuild}, each time into a fresh column.
	 */
	protected abstract void build(VBox content);

	/**
	 * Throws the widgets away and calls {@link #build} again, keeping the scroll position. Widget state (focus, an open
	 * dropdown, the keyboard selection) starts over. Call it on the render thread, e.g. from {@link #tick()} or a
	 * button's action.
	 */
	public void rebuild() {
		float keep = scroll == null ? 0 : scroll.scroll();
		VBox content = new VBox(4, padding);
		build(content);
		// The bar sits in the content's right padding, leaving both sides even with or without it.
		scroll = new ScrollView(content, Float.MAX_VALUE).gutter(Math.max(0, 4 - padding));
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

	/** True while typing in a text field or binding a key, so UI shortcuts and Escape go to the widget. */
	@Override
	public boolean capturesKeyboard() {
		return root != null && root.focused() != null;
	}

	/** The hovered widget's tooltip, from the last frame. */
	@Override
	public @Nullable String tooltip() {
		return root == null ? null : root.tooltip();
	}

	/** The root hosting the widgets, building them first if they haven't been. A {@link #rebuild} replaces it. */
	protected WidgetRoot widgetRoot() {
		return root();
	}
}
