package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Animated;
import dev.myriadclient.api.render.Bezier;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.util.ColorUtil;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** A switch bound to a boolean. */
public class Toggle extends Widget {
	private final Supplier<Boolean> getter;
	private final Consumer<Boolean> setter;
	private final Animated knob;

	public Toggle(Supplier<Boolean> getter, Consumer<Boolean> setter) {
		this.getter = getter;
		this.setter = setter;
		this.knob = new Animated(getter.get() ? 1 : 0);
	}

	@Override
	protected float measure(Canvas canvas, float width) {
		return ROW;
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		boolean on = getter.get();
		knob.animateTo(on ? 1 : 0, 160, Bezier.EASE_OUT_QUINT);
		float t = knob.get();
		float w = 20, h = 10;
		float sx = x + width - w, sy = y + (height - h) / 2;
		int off = theme().surfaceHover.argb();
		int onC = theme().accent.argb();
		c.roundRect(sx, sy, w, h, h / 2, ColorUtil.lerp(off, onC, t));
		float k = h - 4;
		c.circle(sx + 2 + k / 2 + (w - 4 - k) * t, sy + h / 2, k / 2, 0xFFFFFFFF);
		offerTooltip(mx, my);
	}

	@Override
	public boolean isNavigable() {
		return true;
	}

	@Override
	public void activate() {
		setter.accept(!getter.get());
	}

	@Override
	public boolean adjust(int direction) {
		setter.accept(direction > 0);
		return true;
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (button != 0 || !isHovered(mx, my)) return false;
		setter.accept(!getter.get());
		return true;
	}
}
