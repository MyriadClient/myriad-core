package dev.myriadclient.impl.ui;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.Animated;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.api.util.Keybind;
import dev.myriadclient.impl.render.UiRenderer;
import dev.myriadclient.impl.wheel.WheelImpl;
import dev.myriadclient.impl.wheel.WheelSlot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * A wheel over the game while its bind is held: its slots as round buttons on a ring around a disc that names the one
 * the mouse points at. Letting go of the bind runs that one (pointing at the middle runs nothing); a click runs one
 * too, Escape or a right click closes it. Drawn in the desktop's theme, over a blur of the game.
 */
public final class WheelScreen extends Screen {
	/** A slot's button radius, in the desktop's units. */
	private static final float BUTTON = 15;
	/** Space between neighbouring buttons on the ring. */
	private static final float GAP = 9;
	private static final float MIN_RING = 58;

	private final WheelImpl wheel;
	private final Keybind bind;
	private final List<WheelSlot> slots;
	private final Animated open = new Animated(0);
	private final List<Animated> grow = new ArrayList<>();
	private int hovered = -1;
	private boolean done;

	public WheelScreen(WheelImpl wheel, Keybind bind) {
		super(Component.literal(wheel.name()));
		this.wheel = wheel;
		this.bind = bind;
		this.slots = wheel.slots();
		for (int i = 0; i < slots.size(); i++) grow.add(new Animated(0));
	}

	private WindowManager wm() {
		return (WindowManager) Myriad.ui();
	}

	/** The ring's radius: wide enough for every button to have its gap. */
	private float ring() {
		return Math.max(MIN_RING, slots.size() * (BUTTON * 2 + GAP) / (float) (2 * Math.PI));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		WindowManager wm = wm();
		wm.painter().renderOverlay(context, c -> draw(c, wm, wm.toUnits(mouseX), wm.toUnits(mouseY)));
	}

	private void draw(UiRenderer c, WindowManager wm, float mx, float my) {
		ThemeSettings theme = wm.theme();
		open.animateTo(1, theme.durationMs(theme.windowSpeed) * 0.5f, theme.bezier());
		float t = open.get();
		float cx = wm.screenW / 2, cy = wm.screenH / 2;
		float ring = ring(), outer = ring + BUTTON + 9, inner = ring - BUTTON - 7;
		hovered = pick(mx - cx, my - cy, inner);

		c.push();
		c.alpha(t);
		c.rect(0, 0, wm.screenW, wm.screenH, ColorUtil.fade(0x55000000, t));
		c.translate(cx, cy);
		c.scale(0.9f + 0.1f * t);

		// The plate: blurred game, the window colour, the active border's gradient round the edge.
		if (theme.shadow.get()) c.shadow(-outer, -outer, outer * 2, outer * 2, outer, theme.shadowRange.get(), theme.shadowColor.argb());
		c.backdrop(-outer, -outer, outer * 2, outer * 2, outer, 1);
		c.circle(0, 0, outer, theme.windowBackground.argb());
		c.gradientOutline(-outer, -outer, outer * 2, outer * 2, outer, Math.max(1, theme.borderSize.get()), theme.activeBorderFrom.argb(),
			theme.activeBorderTo.argb(), theme.borderAngle.get().floatValue());
		c.circle(0, 0, inner, theme.surface.argb());

		// Where the mouse points, as a mark on the inner disc's edge.
		float dx = mx - cx, dy = my - cy;
		if (hovered >= 0 && dx * dx + dy * dy > 1) {
			float a = (float) Math.atan2(dy, dx), r = inner - 3;
			c.circle((float) Math.cos(a) * r, (float) Math.sin(a) * r, 2.2f, theme.accent.argb());
		}

		float iconSize = c.defaultFontSize() * 1.25f;
		for (int i = 0; i < slots.size(); i++) {
			WheelSlot s = slots.get(i);
			Animated g = grow.get(i);
			g.animateTo(i == hovered ? 1 : 0, 110, theme.bezier());
			double angle = angle(i);
			float x = (float) Math.cos(angle) * ring, y = (float) Math.sin(angle) * ring;
			float r = BUTTON * (1 + 0.16f * g.get());
			boolean exists = s.exists();
			int fill = ColorUtil.lerp(s.isOn() ? ColorUtil.withAlpha(theme.accent.argb(), 70) : theme.surface.argb(), theme.accent.argb(), g.get());
			c.circle(x, y, r, fill);
			if (s.isOn()) c.outline(x - r, y - r, r * 2, r * 2, r, 1, theme.accent.argb());
			int iconColor = !exists ? theme.textDim.argb() : ColorUtil.lerp(s.isOn() ? theme.accent.argb() : theme.text.argb(), theme.windowBackground.argb() | 0xFF000000, g.get());
			String icon = s.icon();
			c.text(FontFamily.MONO, iconSize, icon, x - c.textWidth(FontFamily.MONO, iconSize, icon) / 2, y - c.textHeight(FontFamily.MONO, iconSize) / 2, iconColor);
		}

		// The middle: what letting go now does.
		float size = c.defaultFontSize();
		String title, hint;
		if (hovered >= 0) {
			WheelSlot s = slots.get(hovered);
			title = s.label();
			hint = s.hint() + (s.isOn() ? " · on" : s.kind() == WheelSlot.Kind.MODULE || s.kind() == WheelSlot.Kind.CONTROL ? " · off" : "");
		} else {
			title = wheel.name();
			hint = slots.isEmpty() ? "Nothing on it yet" : "Point at one, let go";
		}
		float maxW = inner * 2 - 10;
		title = c.ellipsize(FontFamily.SANS, size * 1.1f, title, maxW);
		hint = c.ellipsize(FontFamily.SANS, size * 0.85f, hint, maxW);
		float th = c.textHeight(FontFamily.SANS, size * 1.1f), hh = c.textHeight(FontFamily.SANS, size * 0.85f);
		// Pointing at nothing: the wheel's own icon above its name.
		float big = size * 1.6f, ih = hovered >= 0 ? 0 : c.textHeight(FontFamily.MONO, big) + 3;
		float top = -(ih + th + 2 + hh) / 2;
		if (hovered < 0) {
			String icon = wheel.icon();
			c.text(FontFamily.MONO, big, icon, -c.textWidth(FontFamily.MONO, big, icon) / 2, top, theme.accent.argb());
		}
		top += ih;
		c.text(FontFamily.SANS, size * 1.1f, title, -c.textWidth(FontFamily.SANS, size * 1.1f, title) / 2, top, hovered >= 0 ? theme.text.argb() : theme.accent.argb());
		c.text(FontFamily.SANS, size * 0.85f, hint, -c.textWidth(FontFamily.SANS, size * 0.85f, hint) / 2, top + th + 2, theme.textDim.argb());
		c.pop();
	}

	/** Slot {@code i}'s angle: the first at the top, then clockwise. */
	private double angle(int i) {
		return -Math.PI / 2 + i * 2 * Math.PI / Math.max(1, slots.size());
	}

	/** The slot the mouse points towards from the middle, or -1 while it's inside the inner disc. */
	private int pick(float dx, float dy, float inner) {
		if (slots.isEmpty() || dx * dx + dy * dy < (inner * 0.5f) * (inner * 0.5f)) return -1;
		double a = Math.atan2(dy, dx) + Math.PI / 2;
		double step = 2 * Math.PI / slots.size();
		int i = (int) Math.round(a / step);
		return Math.floorMod(i, slots.size());
	}

	/** Closes the wheel, then runs slot {@code index} if there is one (so what it opens, it opens over the game). */
	private void finish(int index) {
		if (done) return;
		done = true;
		onClose();
		if (index >= 0 && index < slots.size()) slots.get(index).run();
	}

	/** The bind let go (it opened this screen from its press, so the release always comes here): run what it points at. */
	@Override
	public boolean keyReleased(KeyEvent event) {
		if (!bind.mouse() && event.key() == bind.code()) finish(hovered);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (bind.mouse() && event.button() == bind.code()) finish(hovered);
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (bind.mouse() && event.button() == bind.code()) return true;
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && hovered >= 0) finish(hovered);
		else if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) finish(-1);
		return true;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
