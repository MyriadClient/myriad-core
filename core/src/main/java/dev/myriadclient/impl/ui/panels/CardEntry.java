package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.render.Animated;
import dev.myriadclient.api.render.Bezier;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.ui.widget.Widget;
import dev.myriadclient.api.util.ColorUtil;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;

/**
 * The card every list entry in the menu uses (modules, HUD elements, addons): a header row tinted while the entry is
 * "on", with a chevron that unfolds a card of details and options underneath. Subclasses say what the entry is and
 * what clicking it does.
 */
abstract class CardEntry extends Widget {
	/** Inner padding of the unfolded card. */
	private static final float PAD_X = 7, PAD_TOP = 5, PAD_BOTTOM = 5;

	private final Animated on, open;
	private VBox body;
	private Object bodyKey;

	CardEntry(boolean on, boolean expanded) {
		this.on = new Animated(on ? 1 : 0);
		this.open = new Animated(expanded ? 1 : 0);
	}

	// ---- what the entry is ----------------------------------------------------------------------------------------

	protected abstract String title();

	/** Tints the header (a module is enabled, a HUD element is shown, an addon loaded). */
	protected abstract boolean isOn();

	protected abstract int accent();

	protected abstract boolean isExpanded();

	protected abstract void setExpanded(boolean expanded);

	/** Fills the unfolded card. */
	protected abstract void buildBody(VBox body);

	/** The card is rebuilt whenever this changes (e.g. the HUD element it edits was added or removed). */
	protected Object bodyKey() {
		return null;
	}

	/** Left click on the header (Enter with the keyboard). Unfolds by default. */
	protected void primary() {
		setExpanded(!isExpanded());
	}

	/** Middle click on the header. */
	protected void middle() {
	}

	/** Shift+right-click on the header. Unfolds by default. */
	protected void shiftSecondary() {
		setExpanded(!isExpanded());
	}

	/** Draws pills at the right of the header ending at {@code right}; returns the space left. */
	protected float drawBadges(Canvas c, float right, float cy) {
		return right;
	}

	protected String tooltipText() {
		return null;
	}

	// ---- layout and drawing -----------------------------------------------------------------------------------------

	private VBox body() {
		Object key = bodyKey();
		if (body == null || !Objects.equals(key, bodyKey)) {
			bodyKey = key;
			body = new VBox(4, 0);
			buildBody(body);
			attachChild(body);
		}
		return body;
	}

	@Override
	protected float measure(Canvas c, float width) {
		open.animateTo(isExpanded() ? 1 : 0, 200, Bezier.EASE_OUT_QUINT);
		float t = open.get();
		float h = ROW + 2;
		if (t > 0.001f || isExpanded()) {
			float bh = body().layout(c, x + PAD_X, y + h + PAD_TOP, width - PAD_X * 2);
			h += (bh + PAD_TOP + PAD_BOTTOM) * t;
		}
		return h;
	}

	@Override
	public float navHeight() {
		return ROW + 2;
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		on.animateTo(isOn() ? 1 : 0, 180, Bezier.EASE_OUT_QUINT);
		float t = on.get(), o = open.get();
		float rowH = ROW + 2;
		boolean hover = mx >= x && my >= y && mx < x + width && my < y + rowH;
		int accent = accent();

		// The unfolded card sits behind the header so the two read as one block.
		if (o > 0.01f && body != null) {
			int card = ColorUtil.withAlpha(theme().surface.argb(), (int) (ColorUtil.alpha(theme().surface.argb()) * 0.55f * o));
			c.roundRect(x, y, width, height, 4, card);
			c.outline(x, y, width, height, 4, 1, ColorUtil.withAlpha(accent, (int) (45 * o)));
		}
		float corner = 4 * (1 - o);
		c.roundRect(x, y, width, rowH, 4, 4, corner, corner, hover ? theme().surfaceHover.argb() : theme().surface.argb());
		if (t > 0.01f) {
			// Square the bottom corners as the card opens, like the header itself.
			c.roundRect(x, y, width * t, rowH, 4, 4, corner, corner, ColorUtil.withAlpha(accent, (int) (55 * t)));
			c.roundRect(x, y + 3, 2, rowH - 6, 1, ColorUtil.withAlpha(accent, (int) (255 * t)));
		}
		float ty = y + (rowH - c.textHeight()) / 2;
		float rx = x + width - 10;
		float right = drawBadges(c, rx - 6, y + rowH / 2);
		c.text(c.ellipsize(c.defaultFont(), c.defaultFontSize(), title(), right - x - 6), x + 6, ty, ColorUtil.lerp(theme().textDim.argb(), theme().text.argb(), t));
		float iconSize = c.defaultFontSize() * 0.75f;
		String chevron = isExpanded() ? "" : "";
		c.text(FontFamily.MONO, iconSize, chevron, rx, y + (rowH - c.textHeight(FontFamily.MONO, iconSize)) / 2,
			isExpanded() ? accent : mx >= rx - 3 && hover ? theme().text.argb() : ColorUtil.withAlpha(theme().textDim.argb(), 150));
		if (o > 0.01f && body != null) {
			c.push();
			c.clip(x, y + rowH, width, height - rowH);
			if (o < 1) c.alpha(o);
			body.render(c, mx, my);
			c.pop();
		}
		if (hover) {
			tooltip = tooltipText();
			if (tooltip != null) offerTooltip(mx, my);
		}
	}

	/**
	 * Draws a small pill ending at {@code right}, vertically centred on {@code cy}; returns its left edge. Tinted
	 * pills take the colour as background; plain ones are a neutral outline.
	 */
	protected float badge(Canvas c, String text, float right, float cy, int color, boolean tinted) {
		float s = c.defaultFontSize() * 0.7f;
		float tw = badgeWidth(c, text, tinted);
		float th = c.textHeight(FontFamily.SANS, s) + 3;
		float bx = right - tw, by = cy - th / 2;
		if (tinted) {
			c.roundRect(bx, by, tw, th, th / 2, ColorUtil.withAlpha(color, 50));
			c.outline(bx, by, tw, th, th / 2, 1, ColorUtil.withAlpha(color, 110));
			c.text(FontFamily.SANS_BOLD, s, text, bx + 4, by + 1.5f, ColorUtil.lerp(color, theme().text.argb(), 0.35f));
		} else {
			c.outline(bx, by, tw, th, th / 2, 1, ColorUtil.withAlpha(color, 90));
			c.text(FontFamily.SANS, s, text, bx + 4, by + 1.5f, color);
		}
		return bx;
	}

	protected static float badgeWidth(Canvas c, String text, boolean tinted) {
		return c.textWidth(tinted ? FontFamily.SANS_BOLD : FontFamily.SANS, c.defaultFontSize() * 0.7f, text) + 8;
	}

	// ---- input ------------------------------------------------------------------------------------------------------

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		float rowH = ROW + 2;
		if (mx >= x && my >= y && mx < x + width && my < y + rowH) {
			boolean chevron = mx >= x + width - 14;
			if (button == 1 && GLFW.glfwGetKey(Minecraft.getInstance().getWindow().handle(), GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS) {
				shiftSecondary();
			} else if (button == 1 || chevron) {
				setExpanded(!isExpanded());
			} else if (button == 0) {
				primary();
			} else if (button == 2) {
				middle();
			}
			return true;
		}
		return isExpanded() && body != null && body.mouseClicked(mx, my, button) && press(body);
	}

	@Override
	public boolean mouseScrolled(float mx, float my, float amount) {
		return isExpanded() && body != null && body.mouseScrolled(mx, my, amount);
	}

	// ---- keyboard: Enter/Space = primary, l/→ unfold, h/← fold, o toggles the fold ----

	@Override
	public boolean isNavigable() {
		return true;
	}

	@Override
	public void activate() {
		primary();
	}

	@Override
	public void activateSecondary() {
		setExpanded(!isExpanded());
	}

	@Override
	public boolean adjust(int direction) {
		boolean want = direction > 0;
		if (isExpanded() == want) return false;
		setExpanded(want);
		return true;
	}

	@Override
	public void collectNavigable(List<Widget> out) {
		if (!isVisible()) return;
		out.add(this);
		if (isExpanded() && body != null) body.collectNavigable(out);
	}
}
