package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Animated;
import dev.myriadclient.api.render.Bezier;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.util.ColorUtil;

import java.util.List;

/**
 * Shows a child clipped to a viewport with smooth scrolling. With {@code fill} the viewport is the height it is
 * given by {@link #setViewportHeight}; otherwise it sizes to its content up to {@code maxHeight}.
 */
public class ScrollView extends Widget {
	private final Widget child;
	private final float maxHeight;
	private final Animated offset = new Animated(0);
	private float target, contentHeight, viewport = -1;

	public ScrollView(Widget child, float maxHeight) {
		this.child = child;
		this.maxHeight = maxHeight;
	}

	/** Fixed viewport height (used by panels to fill their area). */
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
		float scrollbar = max > 0 ? 4 : 0;
		contentHeight = child.layout(canvas, x, y - offset.get(), width - scrollbar);
		view = viewport >= 0 ? viewport : Math.min(maxHeight, contentHeight);
		return view;
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

	/** Scrolls so the span [top, bottom] (panel coordinates) is inside the viewport. */
	public void ensureVisible(float top, float bottom) {
		float margin = 6;
		if (top < y + margin) target -= (y + margin) - top;
		else if (bottom > y + height - margin) target += bottom - (y + height - margin);
		target = Math.max(0, target);
	}

	public float scroll() {
		return target;
	}

	public void setScroll(float value) {
		target = value;
		offset.snap(value);
	}
}
