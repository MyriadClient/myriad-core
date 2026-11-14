package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.ui.draw.Canvas;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Base of the widget toolkit. Layout is a single top-down pass: a parent gives each child its x, y and width, and the
 * child reports its height. Layout and render run every frame, so widgets can read live values directly.
 * Coordinates are panel-local UI units: the same space as the mouse a widget receives, and the canvas it draws on, so a
 * widget draws at its own {@link #x}/{@link #y} rather than at (0,0).
 * <p>
 * Each frame its {@link WidgetRoot} calls {@link #layout} (which calls {@link #measure}) on the whole tree, then
 * {@link #render}. Input arrives afterwards in the same coordinates: a press goes to the visible widgets in tree order
 * until one returns true; the widget that took it then gets the drags and the release. Everything runs on the render
 * thread. A widget belongs to one tree; build new widgets rather than moving one between parents.
 * <p>
 * A widget of your own extends this and draws with the theme's colours:
 *
 * <pre>{@code
 * public class Swatch extends Widget {
 *     private final IntSupplier color;
 *
 *     public Swatch(IntSupplier color) { this.color = color; }
 *
 *     @Override
 *     protected float measure(Canvas canvas, float width) {
 *         return ROW;                                    // one row tall, whatever width it's given
 *     }
 *
 *     @Override
 *     public void render(Canvas c, float mouseX, float mouseY) {
 *         boolean hover = isHovered(mouseX, mouseY);
 *         c.roundRect(x, y, width, height, 4, hover ? theme().surfaceHover.argb() : theme().surface.argb());
 *         c.roundRect(x + 3, y + 3, width - 6, height - 6, 3, color.getAsInt());
 *         offerTooltip(mouseX, mouseY);
 *     }
 * }
 * }</pre>
 */
public abstract class Widget {
	/** The height of a standard row (buttons, fields, toggles, setting rows), in UI units. */
	public static final float ROW = 14;
	/** A standard padding, in UI units. */
	public static final float PAD = 4;

	/**
	 * Where the parent put this widget and how big it is, in panel-local UI units. {@link #layout} sets {@code x},
	 * {@code y} and {@code width} before calling {@link #measure}, and {@code height} from what it returns (0 while
	 * hidden), so all four are valid from {@link #measure}'s start (except {@code height}) through {@link #render} and
	 * the input that follows, until the next frame's layout. Before the first layout they are 0. Read them; let
	 * {@link #layout} write them.
	 */
	protected float x, y, width, height;
	/** Whether the widget shows, read every frame; see {@link #visible(Supplier)}. */
	protected Supplier<Boolean> visible = () -> true;
	/** The tooltip shown while hovered (after {@link #offerTooltip}), or null for none. */
	protected @Nullable String tooltip;
	@Nullable WidgetRoot root;
	@Nullable Widget parent;

	/**
	 * Lays the widget out at (x, y) with the given width; returns its height (0 while it's hidden, without measuring).
	 * Containers call it on their children from their own {@link #measure}.
	 */
	public final float layout(Canvas canvas, float x, float y, float width) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = isVisible() ? measure(canvas, width) : 0;
		return height;
	}

	/**
	 * Compute height for {@code width} and position children. {@link #x}, {@link #y} and {@link #width} are already
	 * set. Called every frame while visible; keep it cheap.
	 */
	protected abstract float measure(Canvas canvas, float width);

	/**
	 * Draws the widget within its box ({@link #x}, {@link #y}, {@link #width}, {@link #height}), with the mouse in the
	 * same coordinates (far off screen while the pointer is outside the panel or a scroll view's viewport). Called
	 * every frame after {@link #layout}, only while visible.
	 */
	public abstract void render(Canvas canvas, float mouseX, float mouseY);

	/**
	 * The narrowest this is still usable at, for a row deciding whether to shrink it or wrap ({@link HBox}), or -1 if it
	 * has no opinion: a row then keeps a fixed child at its width and gives a shared one a small default.
	 */
	@ApiStatus.AvailableSince("1.4")
	public float minWidth(Canvas canvas) {
		return -1;
	}

	/**
	 * A mouse press at ({@code mouseX}, {@code mouseY}); {@code button} is a GLFW button
	 * ({@code InputConstants.MOUSE_BUTTON_*}). Presses go to every visible widget in turn until one returns true, so
	 * check {@link #isHovered} first. Returning true makes this widget receive the drags and the release.
	 */
	public boolean mouseClicked(float mouseX, float mouseY, int button) {
		return false;
	}

	/** The release of a press this widget took, wherever the mouse is now. */
	public boolean mouseReleased(float mouseX, float mouseY, int button) {
		return false;
	}

	/**
	 * The mouse moving with a button down after a press this widget took. {@code dx}/{@code dy} is how far it moved since
	 * the last drag event, as the panel passes it on (Myriad's UI gives it; a host of your own may pass 0).
	 */
	public boolean mouseDragged(float mouseX, float mouseY, int button, float dx, float dy) {
		return false;
	}

	/**
	 * The wheel, {@code amount} notches (positive turning up), while over the widget. Return true to use it; otherwise
	 * the enclosing scroll view scrolls.
	 */
	public boolean mouseScrolled(float mouseX, float mouseY, float amount) {
		return false;
	}

	/**
	 * A key pressed while this widget is focused: {@code key} is its position (an {@code InputConstants.KEY_*} value),
	 * {@code scancode} what it types on the player's layout (as {@code KeyEvent}); {@code modifiers} are {@code InputConstants.MOD_*} bits.
	 */
	public boolean keyPressed(int key, int scancode, int modifiers) {
		return false;
	}

	/** A character typed while this widget is focused. */
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

	/** Whether it shows now: hidden widgets take no room, draw nothing and get no input. */
	public boolean isVisible() {
		return visible.get();
	}

	/** Shows the widget only while {@code visible} says so (read every frame). Returns this widget. */
	public Widget visible(Supplier<Boolean> visible) {
		this.visible = visible;
		return this;
	}

	/**
	 * The tooltip shown while hovered, or null for none; lines split at {@code \n} and wrap, and see {@link #badgeLine}.
	 * Widgets show it by calling {@link #offerTooltip} as they render (the stock ones do). Returns this widget.
	 */
	public Widget tooltip(@Nullable String tooltip) {
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

	/**
	 * Whether the point is in this widget's box (as of the last layout) and inside the visible part of the panel, and
	 * the widget shows.
	 */
	public boolean isHovered(float mx, float my) {
		return isVisible() && mx >= x && my >= y && mx < x + width && my < y + height && (root == null || root.isInView(mx, my));
	}

	/** Its left edge, from the last layout. */
	public float x() {
		return x;
	}

	/** Its top edge, from the last layout. */
	public float y() {
		return y;
	}

	/** Its width, from the last layout. */
	public float width() {
		return width;
	}

	/** Its height, from the last layout (0 while hidden). */
	public float height() {
		return height;
	}

	/** Takes keyboard focus: keys and typed characters now come here, and the panel captures the keyboard. */
	protected void focus() {
		if (root != null) root.setFocused(this);
	}

	/** Whether this widget has keyboard focus. */
	protected boolean isFocused() {
		return root != null && root.focused() == this;
	}

	/** Gives up keyboard focus, if it has it; {@link #onBlur()} runs. */
	protected void blur() {
		if (isFocused()) root.setFocused(null);
	}

	/** Call while rendering when hovered to show this widget's tooltip. */
	protected void offerTooltip(float mx, float my) {
		if (tooltip != null && !tooltip.isEmpty() && root != null && isHovered(mx, my)) root.setTooltip(tooltip);
	}

	/** The live theme, for colours and sizes ({@code theme().accent.argb()}). */
	protected static ThemeSettings theme() {
		return Myriad.ui().theme();
	}

	void attach(WidgetRoot root, @Nullable Widget parent) {
		this.root = root;
		this.parent = parent;
		for (Widget child : ownedChildren) child.attach(root, this);
	}

	private final List<Widget> ownedChildren = new ArrayList<>(0);

	/**
	 * For custom composite widgets (outside of {@link Container}): registers a child so it shares this widget's root
	 * (focus, pressed state, tooltips). Call it when you create the child. You still lay out, draw and pass input to
	 * the child yourself.
	 */
	protected void attachChild(Widget child) {
		ownedChildren.add(child);
		if (root != null) child.attach(root, this);
	}

	/**
	 * Makes {@code child} the widget that receives the rest of this mouse press (drags and the release), unless one was
	 * chosen already. Returns true, to return from {@link #mouseClicked}.
	 */
	protected boolean press(Widget child) {
		if (root != null && root.pressed() == null) root.setPressed(child);
		return true;
	}
}
