package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.ui.ThemeSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Base of the widget toolkit. Layout is a single top-down pass: a parent gives each child its x, y and width, and the
 * child reports its height. Layout and render run every frame, so widgets can read live values directly.
 * Coordinates are panel-local UI units.
 */
public abstract class Widget {
	public static final float ROW = 14;
	public static final float PAD = 4;

	protected float x, y, width, height;
	protected Supplier<Boolean> visible = () -> true;
	protected String tooltip;
	WidgetRoot root;
	Widget parent;

	/** Lays the widget out at (x, y) with the given width; returns its height. */
	public final float layout(Canvas canvas, float x, float y, float width) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = isVisible() ? measure(canvas, width) : 0;
		return height;
	}

	/** Compute height for {@code width} and position children. */
	protected abstract float measure(Canvas canvas, float width);

	public abstract void render(Canvas canvas, float mouseX, float mouseY);

	public boolean mouseClicked(float mouseX, float mouseY, int button) {
		return false;
	}

	public boolean mouseReleased(float mouseX, float mouseY, int button) {
		return false;
	}

	public boolean mouseDragged(float mouseX, float mouseY, int button, float dx, float dy) {
		return false;
	}

	public boolean mouseScrolled(float mouseX, float mouseY, float amount) {
		return false;
	}

	public boolean keyPressed(int key, int scancode, int modifiers) {
		return false;
	}

	public boolean charTyped(char chr, int modifiers) {
		return false;
	}

	/** Called when this widget loses keyboard focus. */
	protected void onBlur() {
	}

	// ---- keyboard navigation -------------------------------------------------------------------------------------

	/** Whether the keyboard selection (j/k, arrows) can land on this widget. */
	public boolean isNavigable() {
		return false;
	}

	/** Enter/Space on the selected widget. */
	public void activate() {
	}

	/** The vim {@code o} key: a secondary action (e.g. fold/unfold a module's settings). */
	public void activateSecondary() {
		activate();
	}

	/** h/l or Left/Right on the selected widget: -1 or +1. Return true if handled. */
	public boolean adjust(int direction) {
		return false;
	}

	/** Height of the part the selection outline should surround (headers of expandable widgets override it). */
	public float navHeight() {
		return height;
	}

	/** Adds the navigable widgets currently on screen, in visual order. Containers recurse into shown children. */
	public void collectNavigable(List<Widget> out) {
		if (isVisible() && isNavigable() && height > 0) out.add(this);
	}

	public boolean isVisible() {
		return visible.get();
	}

	public Widget visible(Supplier<Boolean> visible) {
		this.visible = visible;
		return this;
	}

	public Widget tooltip(String tooltip) {
		this.tooltip = tooltip;
		return this;
	}

	/** Marks a tooltip line drawn as a row of pills instead of text; see {@link #badgeLine}. */
	public static final String BADGE_LINE = "\u0001badges:";

	/**
	 * A tooltip line shown as pills. Append {@code @AARRGGBB} (hex) to a label to tint its pill; untinted labels get a
	 * plain outline. Example: {@code "Does a thing.\n" + Widget.badgeLine("Essentials@FF89B4FA", "Combat")}.
	 */
	public static String badgeLine(String... labels) {
		return BADGE_LINE + String.join("\t", labels);
	}

	public boolean isHovered(float mx, float my) {
		return isVisible() && mx >= x && my >= y && mx < x + width && my < y + height && (root == null || root.isInView(mx, my));
	}

	public float x() {
		return x;
	}

	public float y() {
		return y;
	}

	public float width() {
		return width;
	}

	public float height() {
		return height;
	}

	protected void focus() {
		if (root != null) root.setFocused(this);
	}

	protected boolean isFocused() {
		return root != null && root.focused() == this;
	}

	protected void blur() {
		if (isFocused()) root.setFocused(null);
	}

	/** Call while rendering when hovered to show this widget's tooltip. */
	protected void offerTooltip(float mx, float my) {
		if (tooltip != null && !tooltip.isEmpty() && root != null && isHovered(mx, my)) root.setTooltip(tooltip);
	}

	protected static ThemeSettings theme() {
		return Myriad.ui().theme();
	}

	void attach(WidgetRoot root, Widget parent) {
		this.root = root;
		this.parent = parent;
		for (Widget child : ownedChildren) child.attach(root, this);
	}

	private final List<Widget> ownedChildren = new ArrayList<>(0);

	/**
	 * For custom composite widgets (outside of {@link Container}): registers a child so it shares this widget's root
	 * (focus, pressed state, tooltips). Call it when you create the child.
	 */
	protected void attachChild(Widget child) {
		ownedChildren.add(child);
		if (root != null) child.attach(root, this);
	}

	/** Makes {@code child} the widget that receives the rest of this mouse press (drags and the release). */
	protected boolean press(Widget child) {
		if (root != null && root.pressed() == null) root.setPressed(child);
		return true;
	}
}
