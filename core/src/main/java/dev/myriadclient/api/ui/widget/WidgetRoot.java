package dev.myriadclient.api.ui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.ui.draw.Animated;
import dev.myriadclient.api.ui.draw.Bezier;
import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.util.ColorUtil;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Hosts a widget tree inside a panel: routes input, tracks keyboard focus, the pressed widget (which receives drags
 * and the release), the tooltip of the hovered widget, and the keyboard selection.
 * <p>
 * Keyboard navigation: j/k or Down/Up move the selection, h/l or Left/Right adjust the selected widget (slider
 * steps, toggles, folding), Enter/Space activate it, gg/G jump to the first/last item, Ctrl+d/Ctrl+u move half a page
 * and {@code /} focuses the first text field (usually a filter). Vim keys can be turned off in the theme's Input
 * settings; arrows always work.
 * <p>
 * {@link dev.myriadclient.api.ui.WidgetPanel} makes and feeds one for you. To host widgets in a panel of your own,
 * forward the panel's {@code render} and input calls to it, and its focus to {@code capturesKeyboard}:
 *
 * <pre>{@code
 * public final class MyPanel extends Panel {
 *     private final VBox box = new VBox(4, 6);
 *     private final WidgetRoot root = new WidgetRoot(box);
 *
 *     public MyPanel() {
 *         box.add(new Label("Hello"));
 *         box.add(new Button("Press", () -> Myriad.notifications().info("Example", "Pressed")));
 *     }
 *
 *     @Override public String title() { return "Mine"; }
 *     @Override public void render(Canvas c, float w, float h, float mx, float my) { root.render(c, w, h, mx, my); }
 *     @Override public boolean mouseClicked(float mx, float my, int b) { return root.mouseClicked(mx, my, b); }
 *     @Override public boolean mouseReleased(float mx, float my, int b) { return root.mouseReleased(mx, my, b); }
 *     @Override public boolean mouseDragged(float mx, float my, int b, float dx, float dy) {
 *         return root.mouseDragged(mx, my, b, dx, dy);
 *     }
 *     @Override public boolean mouseScrolled(float mx, float my, float a) { return root.mouseScrolled(mx, my, a); }
 *     @Override public boolean keyPressed(int k, int s, int m) { return root.keyPressed(k, s, m); }
 *     @Override public boolean charTyped(char c, int m) { return root.charTyped(c, m); }
 *     @Override public boolean capturesKeyboard() { return root.focused() != null; }
 *     @Override public @Nullable String tooltip() { return root.tooltip(); }
 * }
 * }</pre>
 */
public final class WidgetRoot {
	private final Widget content;
	private @Nullable Widget focused, pressed;
	private @Nullable String tooltip;
	private float viewW, viewH;
	private @Nullable Widget selected;
	private boolean keyboardMode;
	private boolean pendingG;
	private @Nullable ScrollView scroller;
	private final List<Widget> navigable = new ArrayList<>();
	private final Animated selY = new Animated(0), selH = new Animated(0);

	/**
	 * Hosts {@code content}, the top of the tree; the tree's widgets join this root, so they can take focus and offer
	 * tooltips.
	 */
	public WidgetRoot(Widget content) {
		this.content = content;
		content.attach(this, null);
	}

	/** The top of the tree. */
	public Widget content() {
		return content;
	}

	/**
	 * Lays the tree out at (0,0) with {@code width}, draws it, and draws the keyboard selection; {@code width} and
	 * {@code height} (UI units) are the visible area, outside which nothing counts as hovered. Clears the tooltip,
	 * which hovered widgets offer again as they draw. Call it every frame, before passing input.
	 */
	public void render(Canvas canvas, float width, float height, float mouseX, float mouseY) {
		viewW = width;
		viewH = height;
		tooltip = null;
		content.layout(canvas, 0, 0, width);
		content.render(canvas, mouseX, mouseY);
		navigable.clear();
		content.collectNavigable(navigable);
		if (keyboardMode && selected != null) {
			if (!navigable.contains(selected)) selected = navigable.isEmpty() ? null : nearest(selected.y());
			if (selected != null) drawSelection(canvas);
		}
	}

	/** The scroll view to keep the selection visible in (WidgetPanel sets this). */
	public void setScroller(ScrollView scroller) {
		this.scroller = scroller;
	}

	private @Nullable Widget nearest(float y) {
		Widget best = null;
		float bestD = Float.MAX_VALUE;
		for (Widget w : navigable) {
			float d = Math.abs(w.y() - y);
			if (d < bestD) {
				bestD = d;
				best = w;
			}
		}
		return best;
	}

	private void drawSelection(Canvas c) {
		Widget w = selected;
		selY.animateTo(w.y(), 90, Bezier.EASE_OUT_QUINT);
		selH.animateTo(w.navHeight(), 90, Bezier.EASE_OUT_QUINT);
		int accent = Myriad.ui().theme().accent.argb();
		float x0 = w.x() - 2, wd = w.width() + 4;
		c.push();
		c.clip(0, 0, viewW, viewH);
		c.roundRect(x0, selY.get() - 1, wd, selH.get() + 2, 4, ColorUtil.withAlpha(accent, 28));
		c.outline(x0, selY.get() - 1, wd, selH.get() + 2, 4, 1, ColorUtil.withAlpha(accent, 200));
		c.pop();
	}

	private void select(@Nullable Widget w) {
		if (w == null) return;
		if (selected == null || !keyboardMode) {
			selY.snap(w.y());
			selH.snap(w.navHeight());
		}
		selected = w;
		keyboardMode = true;
		if (scroller != null) scroller.ensureVisible(w.y(), w.y() + w.navHeight());
	}

	private void move(int delta) {
		if (navigable.isEmpty()) return;
		int i = selected == null || !keyboardMode ? (delta > 0 ? -1 : navigable.size()) : navigable.indexOf(selected);
		select(navigable.get(Math.clamp(i + delta, 0, navigable.size() - 1)));
	}

	/** Handles navigation keys; returns true if the key was used. */
	private boolean navigate(int key, int mods) {
		boolean vim = Myriad.ui().theme().vimKeys.get();
		boolean ctrl = (mods & InputConstants.MOD_CONTROL) != 0, shift = (mods & InputConstants.MOD_SHIFT) != 0;
		boolean g = pendingG;
		pendingG = false;
		if (key == InputConstants.KEY_DOWN || (vim && key == InputConstants.KEY_J && !ctrl)) move(1);
		else if (key == InputConstants.KEY_UP || (vim && key == InputConstants.KEY_K && !ctrl)) move(-1);
		else if (vim && ctrl && key == InputConstants.KEY_D) move(8);
		else if (vim && ctrl && key == InputConstants.KEY_U) move(-8);
		else if (key == InputConstants.KEY_PAGEDOWN) move(8);
		else if (key == InputConstants.KEY_PAGEUP) move(-8);
		else if (key == InputConstants.KEY_HOME || (vim && key == InputConstants.KEY_G && !shift && g)) move(-navigable.size());
		else if (key == InputConstants.KEY_END || (vim && key == InputConstants.KEY_G && shift)) move(navigable.size());
		else if (vim && key == InputConstants.KEY_G) pendingG = true;
		else if (key == InputConstants.KEY_SLASH) {
			for (Widget w : navigable) {
				if (w instanceof TextField) {
					select(w);
					w.activate();
					return true;
				}
			}
			return false;
		} else if (selected != null && keyboardMode) {
			if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER || key == InputConstants.KEY_SPACE) selected.activate();
			else if (key == InputConstants.KEY_LEFT || (vim && key == InputConstants.KEY_H)) selected.adjust(-1);
			else if (key == InputConstants.KEY_RIGHT || (vim && key == InputConstants.KEY_L)) selected.adjust(1);
			else if (key == InputConstants.KEY_O && vim) selected.activateSecondary();
			else return false;
		} else if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_SPACE || key == InputConstants.KEY_LEFT || key == InputConstants.KEY_RIGHT
			|| (vim && (key == InputConstants.KEY_H || key == InputConstants.KEY_L))) {
			move(1);
		} else {
			return false;
		}
		return true;
	}

	/** Whether the keyboard selection is showing: a navigation key was pressed since the last click. */
	public boolean isKeyboardMode() {
		return keyboardMode;
	}

	boolean isInView(float mx, float my) {
		return mx >= 0 && my >= 0 && mx < viewW && my < viewH;
	}

	/**
	 * A press: hides the keyboard selection (moving it to the widget pressed), hands the press to the tree, and takes
	 * focus away from the focused widget if nothing took the press.
	 */
	public boolean mouseClicked(float mx, float my, int button) {
		pressed = null;
		keyboardMode = false;
		for (Widget w : navigable) {
			if (mx >= w.x() && mx < w.x() + w.width() && my >= w.y() && my < w.y() + w.navHeight()) selected = w;
		}
		boolean handled = content.mouseClicked(mx, my, button);
		if (!handled && focused != null) setFocused(null);
		return handled;
	}

	/** The release, to the widget that took the press (if any). */
	public boolean mouseReleased(float mx, float my, int button) {
		Widget p = pressed;
		pressed = null;
		return p != null && p.mouseReleased(mx, my, button);
	}

	/** A drag, to the widget that took the press (if any). */
	public boolean mouseDragged(float mx, float my, int button, float dx, float dy) {
		return pressed != null && pressed.mouseDragged(mx, my, button, dx, dy);
	}

	/** The wheel, to the tree. */
	public boolean mouseScrolled(float mx, float my, float amount) {
		return content.mouseScrolled(mx, my, amount);
	}

	/** A key: to the focused widget if there is one, else to keyboard navigation. Returns whether it was used. */
	public boolean keyPressed(int key, int scancode, int modifiers) {
		if (focused != null) return focused.keyPressed(key, scancode, modifiers);
		return navigate(key, modifiers);
	}

	/** A typed character, to the focused widget; false with none focused. */
	public boolean charTyped(char chr, int modifiers) {
		return focused != null && focused.charTyped(chr, modifiers);
	}

	/** The widget with keyboard focus (a text field being typed in, a key bind listening), or null. */
	public @Nullable Widget focused() {
		return focused;
	}

	/** Gives {@code widget} keyboard focus, or takes it away with null; the widget that loses it is told ({@code onBlur}). */
	public void setFocused(@Nullable Widget widget) {
		if (focused == widget) return;
		Widget old = focused;
		focused = widget;
		if (old != null) old.onBlur();
	}

	@Nullable Widget pressed() {
		return pressed;
	}

	void setPressed(@Nullable Widget widget) {
		pressed = widget;
	}

	/** The tooltip a hovered widget offered during the last {@link #render}, or null. */
	public @Nullable String tooltip() {
		return tooltip;
	}

	void setTooltip(String tooltip) {
		this.tooltip = tooltip;
	}
}
