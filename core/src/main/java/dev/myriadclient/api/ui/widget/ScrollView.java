package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.ui.draw.Animated;
import dev.myriadclient.api.ui.draw.Bezier;
import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.util.ColorUtil;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * Shows a child clipped to a viewport with smooth scrolling (24 units a wheel notch, once the child doesn't use the
 * wheel itself). Given a height by {@link #setViewportHeight}, the viewport is that tall; otherwise it sizes to its
 * content up to {@code maxHeight}. The child's width leaves room for the bar whether it's showing or not, so nothing
 * moves when the content outgrows the view. The mouse only reaches the child inside the viewport.
 */
public class ScrollView extends Widget {
	private final Widget child;
	private final float maxHeight;
	private final Animated offset = new Animated(0);
	private float target, contentHeight, viewport = -1, gutter = 4;

	/**
	 * @param child     what scrolls
	 * @param maxHeight the tallest it grows to fit its content, in UI units, before it scrolls (ignored once
	 *                  {@link #setViewportHeight} gave it a height)
	 */
	public ScrollView(Widget child, float maxHeight) {
		this.child = child;
		this.maxHeight = maxHeight;
	}

	/** The width kept free for the bar, 4 unless given: less if the child's own padding already leaves the room. */
	@ApiStatus.AvailableSince("1.4")
	public ScrollView gutter(float width) {
		gutter = width;
		return this;
	}

	/**
	 * Fixed viewport height in UI units (used by panels to fill their area); a negative one goes back to sizing to the
	 * content.
	 */
	public void setViewportHeight(float h) {
		viewport = h;
	}

	@Override
	void attach(WidgetRoot root, Widget parent) {
		super.attach(root, parent);
		child.attach(root, this);
	}

	@Override
	protected float measure(Canvas canvas, float width) {
		float h0 = child.height;
		float view = viewport >= 0 ? viewport : Math.min(maxHeight, Math.max(h0, 1));
		float max = Math.max(0, h0 - view);
		// A new view's child hasn't been laid out yet (height 0): a scroll it was given (a rebuilt panel keeping its
		// place) waits for the first layout instead of going back to the top.
		if (h0 > 0) target = Math.clamp(target, 0, max);
		if (offset.target() != target) offset.animateTo(target, 180, Bezier.EASE_OUT_QUINT);
		contentHeight = child.layout(canvas, x, y - offset.get(), width - gutter);
		return viewport >= 0 ? viewport : Math.min(maxHeight, contentHeight);
	}

	@Override
	public void render(Canvas canvas, float mx, float my) {
		canvas.push();
		canvas.clip(x, y, width, height);
		boolean inside = mx >= x && my >= y && mx < x + width && my < y + height;
		child.render(canvas, inside ? mx : -10000, inside ? my : -10000);
		canvas.pop();
		float max = contentHeight - height;
		if (max > 0) {
			float barH = Math.max(12, height * height / contentHeight);
			float barY = y + (height - barH) * (offset.get() / max);
			canvas.roundRect(x + width - 3, barY, 2, barH, 1, ColorUtil.withAlpha(theme().text.argb(), 70));
		}
	}

	private boolean inside(float mx, float my) {
		return mx >= x && my >= y && mx < x + width && my < y + height;
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (!inside(mx, my)) return false;
		if (child.mouseClicked(mx, my, button)) {
			if (root != null && root.pressed() == null) root.setPressed(child);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(float mx, float my, float amount) {
		if (!inside(mx, my)) return false;
		if (child.mouseScrolled(mx, my, amount)) return true;
		float max = contentHeight - height;
		if (max <= 0) return false;
		target = Math.clamp(target - amount * 24, 0, max);
		return true;
	}

	@Override
	public void collectNavigable(List<Widget> out) {
		if (isVisible()) child.collectNavigable(out);
	}

	/** Scrolls (smoothly) so the span [top, bottom] (panel coordinates) is inside the viewport, with a small margin. */
	public void ensureVisible(float top, float bottom) {
		float margin = 6;
		if (top < y + margin) target -= (y + margin) - top;
		else if (bottom > y + height - margin) target += bottom - (y + height - margin);
		target = Math.max(0, target);
	}

	/** How far it's scrolled (or scrolling to), in UI units from the top. */
	public float scroll() {
		return target;
	}

	/** Jumps to {@code value} UI units from the top, kept within the content once it's laid out. */
	public void setScroll(float value) {
		target = value;
		offset.snap(value);
	}
}
