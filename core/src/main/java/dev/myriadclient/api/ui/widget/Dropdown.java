package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Animated;
import dev.myriadclient.api.render.Bezier;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.util.ColorUtil;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Pick one of several values: click to expand the options inline. */
public class Dropdown<T> extends Widget {
	private final Supplier<List<T>> options;
	private final Supplier<T> getter;
	private final Consumer<T> setter;
	private final Function<T, String> names;
	private final Animated open = new Animated(0);
	private boolean expanded;

	public Dropdown(Supplier<List<T>> options, Supplier<T> getter, Consumer<T> setter, Function<T, String> names) {
		this.options = options;
		this.getter = getter;
		this.setter = setter;
		this.names = names;
	}

	@Override
	protected float measure(Canvas canvas, float width) {
		open.animateTo(expanded ? 1 : 0, 160, Bezier.EASE_OUT_QUINT);
		return ROW + (options.get().size() * (ROW - 2) + 2) * open.get();
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		boolean hover = mx >= x && my >= y && mx < x + width && my < y + ROW;
		// The header lights up on hover; an open list under it keeps the header's own colour.
		int header = hover ? theme().surfaceHover.argb() : theme().surface.argb();
		if (height > ROW + 0.5f) {
			c.roundRect(x, y, width, ROW, 4, 4, 0, 0, header);
			c.roundRect(x, y + ROW, width, height - ROW, 0, 0, 4, 4, theme().surface.argb());
		} else {
			c.roundRect(x, y, width, height, 4, header);
		}
		String value = names.apply(getter.get());
		c.text(c.ellipsize(c.defaultFont(), c.defaultFontSize(), value, width - 16), x + 4, y + (ROW - c.textHeight()) / 2, theme().text.argb());
		c.text(expanded ? "" : "", x + width - 10, y + (ROW - c.textHeight()) / 2, theme().textDim.argb());
		float t = open.get();
		if (t > 0.01f) {
			c.push();
			c.clip(x, y + ROW, width, height - ROW);
			c.alpha(t);
			float oy = y + ROW;
			T current = getter.get();
			for (T option : options.get()) {
				boolean h = mx >= x && my >= oy && mx < x + width && my < oy + ROW - 2 && my < y + height;
				if (option.equals(current)) c.roundRect(x + 2, oy, width - 4, ROW - 2, 3, ColorUtil.withAlpha(theme().accent.argb(), 90));
				else if (h) c.roundRect(x + 2, oy, width - 4, ROW - 2, 3, theme().surfaceHover.argb());
				c.text(names.apply(option), x + 6, oy + (ROW - 2 - c.textHeight()) / 2, theme().text.argb());
				oy += ROW - 2;
			}
			c.pop();
		}
		offerTooltip(mx, my);
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (!isHovered(mx, my)) return false;
		if (my < y + ROW) {
			if (button == 0) expanded = !expanded;
			else if (button == 1) cycle(1);
			return true;
		}
		if (expanded) {
			int i = (int) ((my - y - ROW) / (ROW - 2));
			List<T> list = options.get();
			if (i >= 0 && i < list.size()) {
				setter.accept(list.get(i));
				expanded = false;
			}
			return true;
		}
		return false;
	}

	private void cycle(int dir) {
		List<T> list = options.get();
		int i = list.indexOf(getter.get());
		setter.accept(list.get(Math.floorMod(i + dir, list.size())));
	}

	@Override
	public boolean isNavigable() {
		return true;
	}

	@Override
	public void activate() {
		cycle(1);
	}

	@Override
	public boolean adjust(int direction) {
		cycle(direction);
		return true;
	}

	/** The wheel scrolls the window, never the value: scrolling down a list of settings mustn't change the ones it passes. */
	@Override
	public boolean mouseScrolled(float mx, float my, float amount) {
		return false;
	}
}
