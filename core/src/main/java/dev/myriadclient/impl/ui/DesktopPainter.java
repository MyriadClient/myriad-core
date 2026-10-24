package dev.myriadclient.impl.ui;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.ui.BarWidget;
import dev.myriadclient.api.ui.Desktop;
import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.ui.TitleControl;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.Widget;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.impl.render.UiRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Draws the {@link WindowManager}'s desktop: workspaces (sliding or fading between them), windows with their title
 * bars, borders and shadows, the HUD elements in game and while editing them, the bar, tooltips, and the launcher on
 * its own. It only reads the window manager's state, apart from the frame's mouse position and scale.
 */
final class DesktopPainter {
	private final WindowManager wm;
	private final Minecraft mc = Minecraft.getInstance();
	private final ThemeSettings theme;

	DesktopPainter(WindowManager wm) {
		this.wm = wm;
		this.theme = wm.theme;
	}

	/** Myriad's renderer set up for the theme (font, blur). */
	void prepareRenderer(UiRenderer r) {
		r.setDefaults(theme.fontFamily(), theme.fontSize.get().floatValue());
		// No world, nothing worth blurring: the desktop's own background is drawn as part of the GUI.
		r.setBlur(theme.blur.get() && mc.level != null, theme.blurPasses.get(), theme.blurSize.get().floatValue());
	}

	void renderDesktop(GuiGraphicsExtractor ctx, int guiMouseX, int guiMouseY) {
		wm.updateScale();
		wm.mouseX = wm.toUnits(guiMouseX);
		wm.mouseY = wm.toUnits(guiMouseY);
		UiRenderer c = UiRenderer.get();
		prepareRenderer(c);
		c.begin(ctx, wm.pixelScale);
		try {
			titleTip = null;
			if (mc.level == null) c.gradientRect(0, 0, wm.screenW, wm.screenH, 0, 0xFF0C0C0C, 0xFF151816, 90);
			c.captureBlur();
			if (wm.active != Desktop.HUD_WORKSPACE) c.rect(0, 0, wm.screenW, wm.screenH, theme.desktopTint.argb());

			float t = wm.transition.get();
			if (t < 1 && wm.previous != wm.active) {
				drawWorkspaceLayer(c, wm.workspaces[wm.previous], t, true);
				drawWorkspaceLayer(c, wm.workspaces[wm.active], t, false);
			} else {
				drawWorkspaceLayer(c, wm.workspaces[wm.active], 1, false);
			}
			drawClosing(c);
			if (wm.active == Desktop.HUD_WORKSPACE) drawHudEditorOverlay(c);
			if (theme.bar.get()) drawBar(c);
			if (wm.workspaces[wm.active].isEmpty() && wm.active != Desktop.HUD_WORKSPACE) drawEmptyHint(c);
			wm.launcher.render(c, wm.screenW, wm.screenH, wm.mouseX, wm.mouseY);
			drawTooltip(c);
			wm.notifications().render(c, wm.screenW, wm.screenH, wm.barAtTop() ? 0 : wm.barHeight());
		} finally {
			c.end();
		}
	}

	/** Draws in the desktop's units, at its scale, for screens of Myriad's own (the title screen). */
	void paint(GuiGraphicsExtractor ctx, Consumer<UiRenderer> drawer) {
		wm.updateScale();
		UiRenderer c = UiRenderer.get();
		if (c.isActive()) return;
		prepareRenderer(c);
		c.begin(ctx, wm.pixelScale);
		try {
			drawer.accept(c);
		} finally {
			c.end();
		}
	}

	/** The launcher on its own over the game ({@link LauncherScreen}). */
	void renderLauncher(GuiGraphicsExtractor ctx, int guiMouseX, int guiMouseY) {
		wm.updateScale();
		wm.mouseX = wm.toUnits(guiMouseX);
		wm.mouseY = wm.toUnits(guiMouseY);
		UiRenderer c = UiRenderer.get();
		prepareRenderer(c);
		c.begin(ctx, wm.pixelScale);
		try {
			c.captureBlur();
			wm.launcher.render(c, wm.screenW, wm.screenH, wm.mouseX, wm.mouseY);
		} finally {
			c.end();
		}
	}

	/** Something drawn over the game on its own, like the launcher (a wheel), in the desktop's units and theme. */
	void renderOverlay(GuiGraphicsExtractor ctx, Consumer<UiRenderer> draw) {
		wm.updateScale();
		UiRenderer c = UiRenderer.get();
		prepareRenderer(c);
		c.begin(ctx, wm.pixelScale);
		try {
			c.captureBlur();
			draw.accept(c);
		} finally {
			c.end();
		}
	}

	/**
	 * Myriad's mark on the title screen: the logo, the version, and how to open the menu, in the bottom left corner
	 * just above Minecraft's version line (the one corner every window shape leaves free).
	 */
	public void renderTitleScreen(GuiGraphicsExtractor ctx) {
		wm.updateScale();
		UiRenderer c = UiRenderer.get();
		if (c.isActive()) return;
		prepareRenderer(c);
		c.begin(ctx, wm.pixelScale);
		try {
			float fs = c.defaultFontSize() * 1.25f;
			float size = 16, x = 6, block = Math.max(size, 1 + c.textHeight(FontFamily.SANS, fs) + 2 + c.textHeight());
			float y = wm.screenH - wm.toUnits(12) - 4 - block;
			c.texture(TITLE_LOGO, x, y, size, size, 0, 0, 1, 1, theme.accent.argb());
			c.text(FontFamily.SANS, fs, "Myriad", x + size + 5, y + 1, 0xFFFFFFFF);
			float vx = x + size + 5 + c.textWidth(FontFamily.SANS, fs, "Myriad") + 4;
			c.text(FontFamily.SANS, c.defaultFontSize(), Myriad.version(), vx, y + 1 + (c.textHeight(FontFamily.SANS, fs) - c.textHeight()) / 2, 0xB0FFFFFF);
			c.text(wm.openHint(), x + size + 5, y + 1 + c.textHeight(FontFamily.SANS, fs) + 2, 0xC8FFFFFF);
		} finally {
			c.end();
		}
	}

	/** The logo, white on transparent, tinted with the accent. */
	private static final Identifier TITLE_LOGO = Identifier.fromNamespaceAndPath("myriad", "textures/logo.png");

	/** In game: draw HUD elements and notifications. */
	public void renderHud(GuiGraphicsExtractor ctx) {
		if (mc.gui.hud.isHidden() || wm.isOpen()) return;
		wm.updateScale();
		UiRenderer c = UiRenderer.get();
		if (c.isActive()) return;
		prepareRenderer(c);
		c.begin(ctx, wm.pixelScale);
		try {
			for (WindowImpl w : new ArrayList<>(wm.workspaces[Desktop.HUD_WORKSPACE].floating)) {
				if (!w.isHudElement()) continue;
				Rect r = wm.hud.rect(w);
				drawHudElement(c, w, r, false);
			}
			wm.notifications().render(c, wm.screenW, wm.screenH, 0);
		} finally {
			c.end();
		}
	}

	private void drawHudElement(UiRenderer c, WindowImpl w, Rect r, boolean editing) {
		int depth = c.depth();
		c.push();
		try {
			if (theme.hudBackground.get()) {
				float rad = theme.rounding.get();
				c.backdrop(r.x() - 3, r.y() - 3, r.w() + 6, r.h() + 6, rad, 1);
				c.roundRect(r.x() - 3, r.y() - 3, r.w() + 6, r.h() + 6, rad, theme.windowBackground.argb());
			}
			c.translate(r.x(), r.y());
			w.panel.render(c, r.w(), r.h(), editing ? -1000 : wm.mouseX - r.x(), editing ? -1000 : wm.mouseY - r.y());
		} catch (Throwable t) {
			if (wm.failedPanels.add(w)) wm.LOG.error("HUD panel {} threw while rendering", w.type.id(), t);
		} finally {
			c.restoreDepth(depth + 1);
			c.pop();
		}
	}

	private void drawWorkspaceLayer(UiRenderer c, Workspace ws, float t, boolean outgoing) {
		// Follow the mouse directly while resizing; animate everything else.
		boolean resizing = wm.drag != null && (wm.drag.kind == WindowManager.DragKind.SPLITTER || wm.drag.kind == WindowManager.DragKind.TILE_RESIZE);
		wm.arrange(ws, !resizing);
		c.push();
		float dir = Integer.compare(wm.active, wm.previous);
		switch (theme.workspaceAnimation.get()) {
			case SLIDE -> c.translate((outgoing ? -t : 1 - t) * dir * wm.screenW, 0);
			case SLIDE_VERTICAL -> c.translate(0, (outgoing ? -t : 1 - t) * dir * wm.screenH);
			case FADE -> c.alpha(outgoing ? 1 - t : t);
			case NONE -> {
				if (outgoing) {
					c.pop();
					return;
				}
			}
		}
		boolean interactive = !outgoing && t >= 1;
		for (WindowImpl w : ws.drawOrder()) {
			if (ws.index == Desktop.HUD_WORKSPACE && w.isHudElement()) {
				drawHudElement(c, w, wm.hud.rect(w), true);
				continue;
			}
			drawWindow(c, w, w.current(), w == ws.focused, interactive);
		}
		c.pop();
	}

	private void drawClosing(UiRenderer c) {
		for (WindowImpl w : new ArrayList<>(wm.closing)) {
			if (w.opacity.get() <= 0.01f) {
				wm.closing.remove(w);
				continue;
			}
			if (w.workspace == wm.active) drawWindow(c, w, w.target, false, false);
		}
	}

	private float borderAngle() {
		float base = theme.borderAngle.get().floatValue();
		if (!theme.animateBorder.get() || !theme.animations.get()) return base;
		double secs = (System.nanoTime() - wm.borderEpoch) / 1e9;
		return (float) (base + secs / theme.borderSpeed.get() * 360 % 360);
	}

	private void drawWindow(UiRenderer c, WindowImpl w, Rect r, boolean focused, boolean interactive) {
		if (r.w() < 1 || r.h() < 1) return;
		float op = w.opacity.get();
		float rad = w.fullscreen ? 0 : theme.rounding.get();
		int depth = c.depth();
		c.push();
		try {
			float alpha = op * (focused ? 1f : theme.inactiveOpacity.get().floatValue());
			c.alpha(alpha);
			if (op < 1) {
				// Hyprland "popin": grow from the theme's popin scale around the centre.
				float p = theme.popin.get().floatValue();
				float s = p + (1 - p) * op;
				c.translate(r.centerX(), r.centerY());
				c.scale(s);
				c.translate(-r.centerX(), -r.centerY());
			}
			if (theme.shadow.get() && !w.fullscreen) {
				c.shadow(r.x(), r.y(), r.w(), r.h(), rad, theme.shadowRange.get(), theme.shadowColor.argb());
			}
			c.backdrop(r.x(), r.y(), r.w(), r.h(), rad, 1);
			c.roundRect(r.x(), r.y(), r.w(), r.h(), rad, theme.windowBackground.argb());

			Rect content = wm.contentRect(w, r);
			if (theme.titleBars.get() && !w.fullscreen) drawTitleBar(c, w, r, rad, focused, interactive);

			c.push();
			c.clip(content.x(), content.y(), content.w(), content.h());
			c.translate(content.x(), content.y());
			boolean hoverable = interactive && wm.launcher.isOpen() == false && wm.topWindowAt(wm.mouseX, wm.mouseY) == w;
			float lmx = hoverable ? wm.mouseX - content.x() : -10000, lmy = hoverable ? wm.mouseY - content.y() : -10000;
			int innerDepth = c.depth();
			try {
				w.panel.render(c, content.w(), content.h(), lmx, lmy);
			} catch (Throwable t) {
				c.restoreDepth(innerDepth);
				if (wm.failedPanels.add(w)) wm.LOG.error("Panel {} threw while rendering", w.type.id(), t);
				c.text(" " + t.getClass().getSimpleName() + ": " + t.getMessage(), 6, 6, 0xFFF38BA8);
			}
			c.pop();

			if (!focused && theme.dimInactive.get() > 0) {
				c.roundRect(r.x(), r.y(), r.w(), r.h(), rad, ColorUtil.argb((int) (theme.dimInactive.get() * 255), 0, 0, 0));
			}
			int bs = theme.borderSize.get();
			if (bs > 0) {
				if (focused) c.gradientOutline(r.x(), r.y(), r.w(), r.h(), rad, bs, theme.activeBorderFrom.argb(), theme.activeBorderTo.argb(), borderAngle());
				else c.outline(r.x(), r.y(), r.w(), r.h(), rad, bs, theme.inactiveBorder.argb());
			}
			if (wm.drag != null && wm.drag.kind == WindowManager.DragKind.SWAP && wm.drag.window != w && interactive && r.contains(wm.mouseX, wm.mouseY) && wm.mergeTarget() == null) {
				c.roundRect(r.x(), r.y(), r.w(), r.h(), rad, ColorUtil.withAlpha(theme.accent.argb(), 40));
			}
		} finally {
			c.restoreDepth(depth + 1);
			c.pop();
		}
	}

	private void drawTitleBar(UiRenderer c, WindowImpl w, Rect r, float rad, boolean focused, boolean interactive) {
		float b = theme.borderSize.get();
		c.roundRect(r.x() + b, r.y() + b, r.w() - b * 2, WindowManager.TITLE_H, Math.max(0, rad - b), Math.max(0, rad - b), 0, 0, theme.titleBackground.argb());
		float ty = r.y() + b + (WindowManager.TITLE_H - c.textHeight()) / 2;
		float x = r.x() + b + 6;
		String icon = w.panel.icon() != null ? w.panel.icon() : w.type.icon();
		if (icon != null) {
			c.text(FontFamily.MONO, c.defaultFontSize(), icon, x, ty, focused ? theme.accent.argb() : theme.textDim.argb());
			x += 11;
		}
		var controls = titleControls(w, r);
		float buttons = 26;
		for (var e : controls) buttons += e.getKey().width() + 3;
		float room = r.right() - buttons - x - 4;
		String title = c.ellipsize(FontFamily.SANS_BOLD, c.defaultFontSize(), safeTitle(w), room);
		c.text(FontFamily.SANS_BOLD, c.defaultFontSize(), title, x, ty, focused ? theme.text.argb() : theme.textDim.argb());
		String sub = safeSubtitle(w);
		if (sub != null && !sub.isEmpty()) {
			float sx = x + c.textWidth(FontFamily.SANS_BOLD, c.defaultFontSize(), title) + 5;
			String s = c.ellipsize(FontFamily.SANS, c.defaultFontSize(), sub, x + room - sx);
			c.text(FontFamily.SANS, c.defaultFontSize(), s, sx, ty, ColorUtil.withAlpha(theme.textDim.argb(), focused ? 220 : 150));
		}
		if (wm.mergeTarget() == w) {
			// Dropping here merges the dragged category window into this one.
			c.roundRect(r.x() + b, r.y() + b, r.w() - b * 2, WindowManager.TITLE_H, Math.max(0, rad - b), Math.max(0, rad - b), 0, 0, ColorUtil.withAlpha(theme.accent.argb(), 110));
			String m = "\uf0c1  Merge";
			c.text(FontFamily.SANS_BOLD, c.defaultFontSize(), m, r.centerX() - c.textWidth(FontFamily.SANS_BOLD, c.defaultFontSize(), m) / 2, ty, theme.text.argb());
		}
		// The panel's own controls (a switch, icon buttons), then float and close.
		float cy = r.y() + b + WindowManager.TITLE_H / 2;
		for (var e : controls) {
			TitleControl tc = e.getKey();
			float cx = e.getValue();
			boolean hover = interactive && wm.mouseX >= cx && wm.mouseX < cx + tc.width() && wm.mouseY >= r.y() && wm.mouseY < r.y() + WindowManager.TITLE_H;
			if (hover) titleTip = tc.tooltip();
			if (tc.isToggle()) {
				tc.knob().animateTo(tc.isOn() ? 1 : 0, 160, theme.bezier());
				float t = tc.knob().get(), sw = tc.width() - 2, sh = 8, sx = cx + 1, sy = cy - sh / 2, k = sh - 3;
				c.roundRect(sx, sy, sw, sh, sh / 2, ColorUtil.lerp(hover ? theme.textDim.argb() : theme.surfaceHover.argb(), theme.accent.argb(), t));
				c.circle(sx + 1.5f + k / 2 + (sw - 3 - k) * t, cy, k / 2, 0xFFFFFFFF);
			} else {
				c.text(FontFamily.MONO, c.defaultFontSize(), tc.icon(), cx + 1, ty, hover ? theme.text.argb() : theme.textDim.argb());
			}
		}
		// Buttons: float toggle, close.
		float bx = r.right() - b - 24;
		boolean hoverFloat = interactive && wm.mouseX >= bx && wm.mouseX < bx + 11 && wm.mouseY >= r.y() && wm.mouseY < r.y() + WindowManager.TITLE_H;
		boolean hoverClose = interactive && wm.mouseX >= bx + 12 && wm.mouseX < bx + 23 && wm.mouseY >= r.y() && wm.mouseY < r.y() + WindowManager.TITLE_H;
		c.text(FontFamily.MONO, c.defaultFontSize(), w.floating ? "" : "", bx + 1, ty, hoverFloat ? theme.text.argb() : theme.textDim.argb());
		c.text(FontFamily.MONO, c.defaultFontSize(), "", bx + 13, ty, hoverClose ? 0xFFF38BA8 : theme.textDim.argb());
	}

	/** A title-bar control hovered this frame, whose tooltip shows instead of the panel's. */
	private String titleTip;

	/** The panel's title-bar controls and the x of each, right to left from just before the float and close buttons. */
	List<Map.Entry<TitleControl, Float>> titleControls(WindowImpl w, Rect r) {
		List<TitleControl> all;
		try {
			all = w.panel.titleControls();
		} catch (Throwable t) {
			return List.of();
		}
		List<Map.Entry<TitleControl, Float>> out = new ArrayList<>();
		float x = r.right() - theme.borderSize.get() - 24 - 3;
		for (TitleControl tc : all) {
			if (!tc.isVisible()) continue;
			x -= tc.width();
			out.add(Map.entry(tc, x));
			x -= 3;
		}
		return out;
	}

	private String safeSubtitle(WindowImpl w) {
		try {
			return w.panel.subtitle();
		} catch (Throwable t) {
			return null;
		}
	}

	private String safeTitle(WindowImpl w) {
		try {
			return w.panel.title();
		} catch (Throwable t) {
			return w.type.name();
		}
	}

	private void drawHudEditorOverlay(UiRenderer c) {
		int guide = ColorUtil.withAlpha(theme.accent.argb(), 90);
		c.rect(wm.screenW / 3, 0, 0.5f, wm.screenH, ColorUtil.withAlpha(theme.textDim.argb(), 25));
		c.rect(wm.screenW * 2 / 3, 0, 0.5f, wm.screenH, ColorUtil.withAlpha(theme.textDim.argb(), 25));
		c.rect(0, wm.screenH / 3, wm.screenW, 0.5f, ColorUtil.withAlpha(theme.textDim.argb(), 25));
		c.rect(0, wm.screenH * 2 / 3, wm.screenW, 0.5f, ColorUtil.withAlpha(theme.textDim.argb(), 25));
		for (WindowImpl w : wm.workspaces[Desktop.HUD_WORKSPACE].floating) {
			if (!w.isHudElement()) continue;
			Rect r = wm.hud.rect(w);
			boolean hover = r.inset(-2).contains(wm.mouseX, wm.mouseY);
			boolean dragging = wm.drag != null && wm.drag.window == w;
			c.outline(r.x() - 2, r.y() - 2, r.w() + 4, r.h() + 4, 3, 1, hover || dragging ? theme.accent.argb() : ColorUtil.withAlpha(theme.textDim.argb(), 80));
			if (hover || dragging) {
				String label = safeTitle(w) + "  ·  " + w.anchor.name().toLowerCase().replace('_', ' ');
				float ly = r.y() - 2 - c.textHeight() - 2 < 0 ? r.bottom() + 3 : r.y() - 2 - c.textHeight() - 2;
				c.text(FontFamily.SANS, c.defaultFontSize() * 0.85f, label, r.x(), ly, theme.accent.argb());
			}
			if (dragging) {
				if (wm.hud.snappedX) c.rect(r.centerX(), 0, 0.5f, wm.screenH, guide);
				if (wm.hud.snappedY) c.rect(0, r.centerY(), wm.screenW, 0.5f, guide);
			}
		}
		String hint = "HUD · drag to move · right-click for settings · Alt+Space to add elements";
		c.text(FontFamily.SANS, c.defaultFontSize() * 0.85f, hint, (wm.screenW - c.textWidth(FontFamily.SANS, c.defaultFontSize() * 0.85f, hint)) / 2,
			wm.barAtTop() ? wm.barHeight() + 6 : 6, theme.textDim.argb());
	}

	private void drawEmptyHint(UiRenderer c) {
		String a = "Workspace " + wm.active + " is empty";
		String b = wm.modName() + "+Space to open something · " + wm.modName() + "+1-9 to switch";
		float size = c.defaultFontSize();
		c.text(FontFamily.SANS_BOLD, size * 1.4f, a, (wm.screenW - c.textWidth(FontFamily.SANS_BOLD, size * 1.4f, a)) / 2, wm.screenH / 2 - 14, theme.text.argb());
		c.text(b, (wm.screenW - c.textWidth(b)) / 2, wm.screenH / 2 + 4, theme.textDim.argb());
	}

	private void drawTooltip(UiRenderer c) {
		if (wm.drag != null || wm.launcher.isOpen()) return;
		WindowImpl w = wm.topWindowAt(wm.mouseX, wm.mouseY);
		if (w == null) return;
		String tip = titleTip;
		try {
			if (tip == null) tip = w.panel.tooltip();
		} catch (Throwable t) {
			return;
		}
		if (tip == null || tip.isBlank()) return;
		float max = 180;
		float lh = c.textHeight() + 1;
		float bs = c.defaultFontSize() * 0.75f, bh = c.textHeight(FontFamily.SANS, bs) + 4;
		// Plain lines wrap; badge lines (Widget.badgeLine) become a row of pills.
		List<Object> rows = new ArrayList<>();
		float tw = 0, th = 6;
		for (String part : tip.split("\n", -1)) {
			if (part.startsWith(Widget.BADGE_LINE)) {
				String[] labels = part.substring(Widget.BADGE_LINE.length()).split("\t");
				float rw = 0;
				for (String l : labels) rw += c.textWidth(FontFamily.SANS_BOLD, bs, badgeText(l)) + 12;
				tw = Math.max(tw, rw - 4);
				th += bh + 2;
				rows.add(labels);
				continue;
			}
			for (String l : Label.wrap(c, c.defaultFont(), c.defaultFontSize(), part, max)) {
				tw = Math.max(tw, c.textWidth(l));
				th += lh;
				rows.add(l);
			}
		}
		float x = Math.min(wm.mouseX + 8, wm.screenW - tw - 10), y = Math.min(wm.mouseY + 10, wm.screenH - th - 4);
		// The theme's window look, as the launcher has it: its backdrop, background, rounding (smaller, as it's small) and border.
		float r = Math.min(theme.rounding.get(), 6);
		c.backdrop(x, y, tw + 8, th, r, 1);
		c.roundRect(x, y, tw + 8, th, r, ColorUtil.withAlpha(theme.windowBackground.argb(), Math.max(0xE0, ColorUtil.alpha(theme.windowBackground.argb()))));
		c.gradientOutline(x, y, tw + 8, th, r, Math.max(1, theme.borderSize.get()), theme.activeBorderFrom.argb(), theme.activeBorderTo.argb(), theme.borderAngle.get().floatValue());
		float ly = y + 3;
		for (Object row : rows) {
			if (row instanceof String l) {
				c.text(l, x + 4, ly, theme.text.argb());
				ly += lh;
				continue;
			}
			float bx = x + 4;
			ly += 1;
			for (String label : (String[]) row) {
				String text = badgeText(label);
				Integer tint = badgeTint(label);
				float pw = c.textWidth(FontFamily.SANS_BOLD, bs, text) + 8;
				if (tint != null) {
					c.roundRect(bx, ly, pw, bh, bh / 2, ColorUtil.withAlpha(tint, 50));
					c.outline(bx, ly, pw, bh, bh / 2, 1, ColorUtil.withAlpha(tint, 110));
					c.text(FontFamily.SANS_BOLD, bs, text, bx + 4, ly + 2, ColorUtil.lerp(tint, theme.text.argb(), 0.35f));
				} else {
					c.outline(bx, ly, pw, bh, bh / 2, 1, ColorUtil.withAlpha(theme.textDim.argb(), 90));
					c.text(FontFamily.SANS, bs, text, bx + 4, ly + 2, theme.textDim.argb());
				}
				bx += pw + 4;
			}
			ly += bh + 1;
		}
	}

	private static String badgeText(String label) {
		int at = label.lastIndexOf('@');
		return at > 0 && label.length() - at == 9 ? label.substring(0, at) : label;
	}

	private static Integer badgeTint(String label) {
		int at = label.lastIndexOf('@');
		if (at <= 0 || label.length() - at != 9) return null;
		try {
			return (int) Long.parseLong(label.substring(at + 1), 16);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// ---- bar ------------------------------------------------------------------------------------------------------

	record BarSlot(BarWidget widget, float x, float w) {
	}

	List<BarSlot> barLayout(UiRenderer c) {
		float h = wm.barHeight();
		List<BarSlot> slots = new ArrayList<>();
		Map<BarWidget.Side, List<BarWidget>> sides = new EnumMap<>(BarWidget.Side.class);
		for (BarWidget w : Myriad.barWidgets()) sides.computeIfAbsent(w.side(), k -> new ArrayList<>()).add(w);
		for (List<BarWidget> l : sides.values()) l.sort(Comparator.comparingInt(BarWidget::order));
		float pad = 6, spacing = 8;
		float x = pad;
		for (BarWidget w : sides.getOrDefault(BarWidget.Side.LEFT, List.of())) {
			float ww = widthOf(w, c, h);
			if (ww <= 0) continue;
			slots.add(new BarSlot(w, x, ww));
			x += ww + spacing;
		}
		float right = wm.screenW - pad;
		List<BarWidget> rightList = new ArrayList<>(sides.getOrDefault(BarWidget.Side.RIGHT, List.of()));
		Collections.reverse(rightList);
		for (BarWidget w : rightList) {
			float ww = widthOf(w, c, h);
			if (ww <= 0) continue;
			right -= ww;
			slots.add(new BarSlot(w, right, ww));
			right -= spacing;
		}
		List<BarWidget> center = sides.getOrDefault(BarWidget.Side.CENTER, List.of());
		float total = 0;
		for (BarWidget w : center) total += Math.max(0, widthOf(w, c, h)) + spacing;
		float cx = (wm.screenW - total + spacing) / 2;
		for (BarWidget w : center) {
			float ww = widthOf(w, c, h);
			if (ww <= 0) continue;
			slots.add(new BarSlot(w, cx, ww));
			cx += ww + spacing;
		}
		return slots;
	}

	private static float widthOf(BarWidget w, UiRenderer c, float h) {
		try {
			return w.width(c, h);
		} catch (Throwable t) {
			return 0;
		}
	}

	private void drawBar(UiRenderer c) {
		float h = wm.barHeight();
		float y = wm.barAtTop() ? 0 : wm.screenH - h;
		c.backdrop(0, y, wm.screenW, h, 0, 1);
		c.rect(0, y, wm.screenW, h, theme.barBackground.argb());
		for (BarSlot s : barLayout(c)) {
			int depth = c.depth();
			try {
				s.widget.render(c, s.x, y, s.w, h, wm.mouseX, wm.mouseY);
			} catch (Throwable t) {
				c.restoreDepth(depth);
			}
		}
	}
}
