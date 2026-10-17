package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.Animated;
import dev.myriadclient.api.render.Bezier;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.util.ColorUtil;
import org.lwjgl.glfw.GLFW;

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
 */
public final class WidgetRoot {
	private final Widget content;
	private Widget focused, pressed;
	private String tooltip;
	private float viewW, viewH;
	private Widget selected;
	private boolean keyboardMode;
	private boolean pendingG;
	private ScrollView scroller;
	private final List<Widget> navigable = new ArrayList<>();
	private final Animated selY = new Animated(0), selH = new Animated(0);

	public WidgetRoot(Widget content) {
		this.content = content;
		content.attach(this, null);
	}

	public Widget content() {
		return content;
	}

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

	private Widget nearest(float y) {
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

	private void select(Widget w) {
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
		boolean ctrl = (mods & GLFW.GLFW_MOD_CONTROL) != 0, shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
		boolean g = pendingG;
		pendingG = false;
		if (key == GLFW.GLFW_KEY_DOWN || (vim && key == GLFW.GLFW_KEY_J && !ctrl)) move(1);
		else if (key == GLFW.GLFW_KEY_UP || (vim && key == GLFW.GLFW_KEY_K && !ctrl)) move(-1);
		else if (vim && ctrl && key == GLFW.GLFW_KEY_D) move(8);
		else if (vim && ctrl && key == GLFW.GLFW_KEY_U) move(-8);
		else if (key == GLFW.GLFW_KEY_PAGE_DOWN) move(8);
		else if (key == GLFW.GLFW_KEY_PAGE_UP) move(-8);
		else if (key == GLFW.GLFW_KEY_HOME || (vim && key == GLFW.GLFW_KEY_G && !shift && g)) move(-navigable.size());
		else if (key == GLFW.GLFW_KEY_END || (vim && key == GLFW.GLFW_KEY_G && shift)) move(navigable.size());
		else if (vim && key == GLFW.GLFW_KEY_G) pendingG = true;
		else if (key == GLFW.GLFW_KEY_SLASH) {
			for (Widget w : navigable) {
				if (w instanceof TextField) {
					select(w);
					w.activate();
					return true;
				}
			}
			return false;
		} else if (selected != null && keyboardMode) {
			if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE) selected.activate();
			else if (key == GLFW.GLFW_KEY_LEFT || (vim && key == GLFW.GLFW_KEY_H)) selected.adjust(-1);
			else if (key == GLFW.GLFW_KEY_RIGHT || (vim && key == GLFW.GLFW_KEY_L)) selected.adjust(1);
			else if (key == GLFW.GLFW_KEY_O && vim) selected.activateSecondary();
			else return false;
		} else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT
			|| (vim && (key == GLFW.GLFW_KEY_H || key == GLFW.GLFW_KEY_L))) {
			move(1);
		} else {
			return false;
		}
		return true;
	}

	public boolean isKeyboardMode() {
		return keyboardMode;
	}

	boolean isInView(float mx, float my) {
		return mx >= 0 && my >= 0 && mx < viewW && my < viewH;
	}

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

	public boolean mouseReleased(float mx, float my, int button) {
		Widget p = pressed;
		pressed = null;
		return p != null && p.mouseReleased(mx, my, button);
	}

	public boolean mouseDragged(float mx, float my, int button, float dx, float dy) {
		return pressed != null && pressed.mouseDragged(mx, my, button, dx, dy);
	}

	public boolean mouseScrolled(float mx, float my, float amount) {
		return content.mouseScrolled(mx, my, amount);
	}

	public boolean keyPressed(int key, int scancode, int modifiers) {
		if (focused != null) return focused.keyPressed(key, scancode, modifiers);
		return navigate(key, modifiers);
	}

	public boolean charTyped(char chr, int modifiers) {
		return focused != null && focused.charTyped(chr, modifiers);
	}

	public Widget focused() {
		return focused;
	}

	public void setFocused(Widget widget) {
		if (focused == widget) return;
		Widget old = focused;
		focused = widget;
		if (old != null) old.onBlur();
	}

	Widget pressed() {
		return pressed;
	}

	void setPressed(Widget widget) {
		pressed = widget;
	}

	public String tooltip() {
		return tooltip;
	}

	void setTooltip(String tooltip) {
		this.tooltip = tooltip;
	}
}
