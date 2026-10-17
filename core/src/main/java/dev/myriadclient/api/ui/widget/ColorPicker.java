package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Animated;
import dev.myriadclient.api.render.Bezier;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.util.ColorUtil;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Inline HSB colour picker: saturation/brightness square, hue and alpha bars, mode
 * buttons (static / rainbow / theme accent) and a hex field. Collapsed it is a swatch.
 */
public class ColorPicker extends Widget {
	private static final float SQUARE = 64, BAR = 7;

	private final Supplier<SettingColor> getter;
	private final Consumer<SettingColor> setter;
	private final Animated open = new Animated(0);
	private final TextField hex;
	private boolean expanded;
	private int dragging = -1; // 0 = square, 1 = hue, 2 = alpha
	private float[] hsb;
	private int alpha;

	public ColorPicker(Supplier<SettingColor> getter, Consumer<SettingColor> setter) {
		this.getter = getter;
		this.setter = setter;
		this.hex = new TextField(() -> ColorUtil.toHex(getter.get().color())).onSubmit(s -> {
			try {
				int c = ColorUtil.parseHex(s);
				setter.accept(new SettingColor(c, SettingColor.Mode.STATIC));
				sync();
			} catch (NumberFormatException ignored) {
			}
		});
		sync();
	}

	private void sync() {
		int c = getter.get().color();
		hsb = ColorUtil.toHsb(c);
		alpha = ColorUtil.alpha(c);
	}

	@Override
	void attach(WidgetRoot root, Widget parent) {
		super.attach(root, parent);
		hex.attach(root, this);
	}

	private static final float SWATCH = 10;
	private static final SettingColor.Mode[] ROLES = {SettingColor.Mode.ACCENT, SettingColor.Mode.SECONDARY, SettingColor.Mode.RED,
		SettingColor.Mode.GREEN, SettingColor.Mode.YELLOW, SettingColor.Mode.BLUE, SettingColor.Mode.MAGENTA, SettingColor.Mode.CYAN, SettingColor.Mode.TEXT};

	private float bodyHeight() {
		return 4 + SQUARE + 4 + BAR + 4 + BAR + 4 + SWATCH + 4 + ROW + 4 + ROW;
	}

	/** y of the theme swatch row, relative to the widget. */
	private float swatchY() {
		return ROW + 4 + SQUARE + 4 + BAR + 4 + BAR + 4;
	}

	@Override
	protected float measure(Canvas canvas, float width) {
		open.animateTo(expanded ? 1 : 0, 180, Bezier.EASE_OUT_QUINT);
		float h = ROW + bodyHeight() * open.get();
		hex.layout(canvas, x, y + swatchY() + SWATCH + 4 + ROW + 4, width);
		return h;
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		if (dragging < 0 && !hex.isFocused()) sync();
		SettingColor value = getter.get();
		boolean hover = mx >= x && my >= y && mx < x + width && my < y + ROW;
		c.roundRect(x, y, width, ROW, 4, hover ? theme().surfaceHover.argb() : theme().surface.argb());
		checker(c, x + width - 26, y + 3, 22, ROW - 6);
		c.roundRect(x + width - 26, y + 3, 22, ROW - 6, 3, value.argb());
		String label = value.mode() == SettingColor.Mode.STATIC ? ColorUtil.toHex(value.color())
			: value.mode().isThemeRole() ? "theme · " + value.mode().name().toLowerCase() : value.mode().name().toLowerCase();
		c.text(label, x + 4, y + (ROW - c.textHeight()) / 2, theme().textDim.argb());

		float t = open.get();
		if (t <= 0.01f) return;
		c.push();
		c.clip(x, y + ROW, width, height - ROW);
		c.alpha(t);
		float by = y + ROW + 4;
		// saturation/brightness square
		int hue = ColorUtil.hsb(hsb[0], 1, 1, 255);
		c.gradientRect(x, by, width, SQUARE, 3, 0xFFFFFFFF, hue, 0);
		c.gradientRect(x, by, width, SQUARE, 3, 0x00000000, 0xFF000000, 90);
		float sx = x + hsb[1] * width, sy = by + (1 - hsb[2]) * SQUARE;
		c.outline(sx - 3, sy - 3, 6, 6, 3, 1.5f, 0xFFFFFFFF);
		by += SQUARE + 4;
		// hue bar: six gradient segments
		float seg = width / 6f;
		for (int i = 0; i < 6; i++) {
			c.gradientRect(x + seg * i, by, seg + 0.5f, BAR, 0, ColorUtil.hsb(i / 6f, 1, 1, 255), ColorUtil.hsb((i + 1) / 6f, 1, 1, 255), 0);
		}
		c.rect(x + hsb[0] * width - 1, by - 1, 2, BAR + 2, 0xFFFFFFFF);
		by += BAR + 4;
		// alpha bar
		checker(c, x, by, width, BAR);
		int opaque = ColorUtil.withAlpha(ColorUtil.hsb(hsb[0], hsb[1], hsb[2], 255), 255);
		c.gradientRect(x, by, width, BAR, 0, ColorUtil.withAlpha(opaque, 0), opaque, 0);
		c.rect(x + alpha / 255f * width - 1, by - 1, 2, BAR + 2, 0xFFFFFFFF);
		by += BAR + 4;
		// theme role swatches: picking one makes the colour follow the theme
		float sw = Math.min(SWATCH + 4, width / ROLES.length);
		for (int i = 0; i < ROLES.length; i++) {
			float sx2 = x + i * sw;
			int rc = SettingColor.role(ROLES[i]).argb();
			c.roundRect(sx2 + 1, by, SWATCH, SWATCH, SWATCH / 2, rc);
			if (value.mode() == ROLES[i]) c.outline(sx2, by - 1, SWATCH + 2, SWATCH + 2, SWATCH / 2 + 1, 1.2f, theme().text.argb());
		}
		by += SWATCH + 4;
		// fixed colour / rainbow
		String[] names = {"Static", "Rainbow"};
		SettingColor.Mode[] modes = {SettingColor.Mode.STATIC, SettingColor.Mode.RAINBOW};
		float bw = (width - 4) / 2;
		for (int i = 0; i < 2; i++) {
			float bx = x + i * (bw + 4);
			boolean active = value.mode() == modes[i];
			boolean h = mx >= bx && my >= by && mx < bx + bw && my < by + ROW;
			c.roundRect(bx, by, bw, ROW, 4, active ? ColorUtil.withAlpha(theme().accent.argb(), 150) : h ? theme().surfaceHover.argb() : theme().surface.argb());
			c.text(names[i], bx + (bw - c.textWidth(names[i])) / 2, by + (ROW - c.textHeight()) / 2, theme().text.argb());
		}
		hex.render(c, mx, my);
		c.pop();
	}

	@Override
	public boolean isNavigable() {
		return true;
	}

	@Override
	public void activate() {
		expanded = !expanded;
	}

	/** h/l cycle through the theme colours. */
	@Override
	public boolean adjust(int direction) {
		SettingColor cur = getter.get();
		int i = Arrays.asList(ROLES).indexOf(cur.mode());
		int next = Math.floorMod((i < 0 ? (direction > 0 ? -1 : 0) : i) + direction, ROLES.length);
		setter.accept(new SettingColor(ColorUtil.withAlpha(0xFFFFFF, ColorUtil.alpha(cur.color())), ROLES[next]));
		return true;
	}

	private static void checker(Canvas c, float x, float y, float w, float h) {
		c.rect(x, y, w, h, 0xFFCCCCCC);
		float s = 3;
		for (float cx = 0; cx < w; cx += s) {
			for (float cy = 0; cy < h; cy += s) {
				if (((int) (cx / s) + (int) (cy / s)) % 2 == 0) c.rect(x + cx, y + cy, Math.min(s, w - cx), Math.min(s, h - cy), 0xFF999999);
			}
		}
	}

	private void apply() {
		int rgb = ColorUtil.hsb(hsb[0], hsb[1], hsb[2], alpha);
		// Editing the colour by hand detaches it from the theme.
		setter.accept(new SettingColor(rgb, getter.get().mode().isThemeRole() ? SettingColor.Mode.STATIC : getter.get().mode()));
	}

	private void drag(float mx, float my) {
		float by = y + ROW + 4;
		switch (dragging) {
			case 0 -> {
				hsb[1] = Math.clamp((mx - x) / width, 0, 1);
				hsb[2] = 1 - Math.clamp((my - by) / SQUARE, 0, 1);
			}
			case 1 -> hsb[0] = Math.clamp((mx - x) / width, 0, 0.999f);
			case 2 -> alpha = Math.round(Math.clamp((mx - x) / width, 0, 1) * 255);
			default -> {
				return;
			}
		}
		apply();
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (!isHovered(mx, my)) return false;
		if (my < y + ROW) {
			expanded = !expanded;
			return true;
		}
		if (!expanded) return false;
		if (hex.mouseClicked(mx, my, button)) return true;
		float by = y + ROW + 4;
		if (my >= by && my < by + SQUARE) dragging = 0;
		else if (my >= by + SQUARE + 4 && my < by + SQUARE + 4 + BAR) dragging = 1;
		else if (my >= by + SQUARE + 8 + BAR && my < by + SQUARE + 8 + BAR * 2) dragging = 2;
		else {
			float sy = y + swatchY();
			if (my >= sy - 1 && my < sy + SWATCH + 1) {
				float sw = Math.min(SWATCH + 4, width / ROLES.length);
				int i = (int) ((mx - x) / sw);
				if (i >= 0 && i < ROLES.length) setter.accept(new SettingColor(ColorUtil.withAlpha(0xFFFFFF, ColorUtil.alpha(getter.get().color())), ROLES[i]));
			} else if (my >= sy + SWATCH + 4 && my < sy + SWATCH + 4 + ROW) {
				SettingColor.Mode m = mx < x + width / 2 ? SettingColor.Mode.STATIC : SettingColor.Mode.RAINBOW;
				SettingColor cur = getter.get();
				// Leaving a theme role keeps what it looked like.
				setter.accept(new SettingColor(cur.mode().isThemeRole() ? cur.argb() : cur.color(), m));
				sync();
			}
			return true;
		}
		drag(mx, my);
		return true;
	}

	@Override
	public boolean mouseDragged(float mx, float my, int button, float dx, float dy) {
		if (dragging < 0) return false;
		drag(mx, my);
		return true;
	}

	@Override
	public boolean mouseReleased(float mx, float my, int button) {
		dragging = -1;
		return true;
	}
}
