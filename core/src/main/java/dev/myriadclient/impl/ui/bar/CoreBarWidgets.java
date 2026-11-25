package dev.myriadclient.impl.ui.bar;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.AddonContext;
import dev.myriadclient.api.ui.BarWidget;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.ui.Ui;
import dev.myriadclient.api.ui.layout.Layout;
import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.draw.FontFamily;
import dev.myriadclient.api.ui.draw.MyriadLogo;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.impl.render.UiRenderer;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.ui.WindowManager;
import dev.myriadclient.impl.ui.panels.CorePanels;
import dev.myriadclient.impl.update.UpdateManager;
import net.minecraft.client.Minecraft;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * The stock bar: launcher button and workspaces on the left, focused window in the middle, clock on the right (with a
 * count of available updates when there are any).
 */
public final class CoreBarWidgets {
	private CoreBarWidgets() {
	}

	private static ThemeSettings theme() {
		return Myriad.ui().theme();
	}

	public static void register(AddonContext ctx, WindowManager wm) {
		ctx.registerBarWidget(new BarWidget(ctx.id("launcher"), "Launcher", BarWidget.Side.LEFT, 0) {
			@Override
			public float width(Canvas c, float h) {
				return h - 2;
			}

			@Override
			public void render(Canvas c, float x, float y, float w, float h, float mx, float my) {
				boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
				MyriadLogo.draw(c, x, y + 1, h - 2, hover ? theme().text.argb() : theme().accent.argb(), 0xFF0C0C0C);
			}

			@Override
			public boolean mouseClicked(float mx, float my, int button) {
				wm.toggleLauncher();
				return true;
			}
		});

		ctx.registerBarWidget(new BarWidget(ctx.id("workspaces"), "Workspaces", BarWidget.Side.LEFT, 10) {
			private static final float CELL = 12;
			/** The square's corners (Dwindle's, the HUD's). */
			private static final float SQUARE_ROUNDING = 1.5f;
			/** Always shown; the rest appear while they have windows (or you're on them), like Omarchy's bar. */
			private static final int ALWAYS = 4;

			/** The workspaces to show, in order, ending with the HUD (0). */
			private int[] shown() {
				int active = wm.activeWorkspace();
				int[] out = new int[Ui.WORKSPACES + 1];
				int n = 0;
				for (int ws = 1; ws <= Ui.WORKSPACES; ws++) {
					if (ws <= ALWAYS || ws == active || wm.windowCount(ws) > 0) out[n++] = ws;
				}
				out[n++] = Ui.HUD_WORKSPACE;
				return Arrays.copyOf(out, n);
			}

			@Override
			public float width(Canvas c, float h) {
				return CELL * shown().length + 2;
			}

			@Override
			public void render(Canvas c, float x, float y, float w, float h, float mx, float my) {
				int active = wm.activeWorkspace();
				int[] shown = shown();
				for (int i = 0; i < shown.length; i++) {
					int ws = shown[i];
					// The HUD sits a little apart from the numbers.
					float cx = x + i * CELL + (ws == Ui.HUD_WORKSPACE ? 2 : 0);
					boolean isActive = ws == active;
					boolean occupied = wm.windowCount(ws) > 0;
					boolean hover = mx >= cx && mx < cx + CELL && my >= y && my < y + h;
					// A true square, centred in its cell, on whole pixels (the same crisp square for every one); the active
					// one's shape says its layout (a circle for Columns, a square for Dwindle, rounder for any other), and it
					// drops its number. The HUD's, with no layout, is the plain square.
					float ps = c.pixelScale();
					float size = Math.round(Math.min(CELL - 2, h - 4) * ps) / ps;
					float sx = Math.round((cx + (CELL - size) / 2) * ps) / ps, sy = Math.round((y + (h - size) / 2) * ps) / ps;
					if (isActive && ws != Ui.HUD_WORKSPACE) {
						c.roundRect(sx, sy, size, size, layoutRounding(wm.layout(ws), size), ColorUtil.withAlpha(theme().accent.argb(), 220));
						continue;
					}
					if (isActive) c.roundRect(sx, sy, size, size, SQUARE_ROUNDING, ColorUtil.withAlpha(theme().accent.argb(), 220));
					else if (hover) c.roundRect(sx, sy, size, size, 2.5f, theme().surfaceHover.argb());
					String label = ws == Ui.HUD_WORKSPACE ? "\uf108" : String.valueOf(ws);
					FontFamily f = ws == Ui.HUD_WORKSPACE ? FontFamily.MONO : FontFamily.SANS_BOLD;
					int color = isActive ? 0xFF11111B : occupied ? theme().text.argb() : ColorUtil.withAlpha(theme().textDim.argb(), 140);
					float fs = c.defaultFontSize() * 0.9f;
					// Centred on its ink, not its advance and line: the HUD's icon glyph sits off both.
					float inkX = c instanceof UiRenderer u ? u.inkOffset(f, fs, label) : 0, inkY = c instanceof UiRenderer u ? u.inkOffsetY(f, fs, label) : 0;
					float tw = c.textWidth(f, fs, label);
					c.text(f, fs, label, sx + (size - tw) / 2 - inkX, sy + (size - c.textHeight(f, fs)) / 2 - inkY, color);
				}
			}

			@Override
			public boolean mouseClicked(float mx, float my, int button) {
				int[] shown = shown();
				int i = (int) (mx / CELL);
				if (i < 0 || i >= shown.length) return true;
				// The workspace you're on, clicked again: its next layout (and its square's shape with it).
				if (shown[i] == wm.activeWorkspace() && shown[i] != Ui.HUD_WORKSPACE) wm.runBind("layout");
				else wm.switchWorkspace(shown[i]);
				return true;
			}

			/** How round the active square is for {@code layout}: a circle for Columns, square for Dwindle, between for others. */
			private static float layoutRounding(Layout layout, float size) {
				String path = layout.id().path();
				if (path.equals("columns")) return size / 2;
				if (path.equals("dwindle")) return SQUARE_ROUNDING;
				return size / 4;
			}
		});

		ctx.registerBarWidget(new BarWidget(ctx.id("layout"), "Layout", BarWidget.Side.LEFT, 20) {
			@Override
			public float width(Canvas c, float h) {
				return c.textWidth(FontFamily.SANS, c.defaultFontSize() * 0.85f, wm.layoutName(wm.activeWorkspace()));
			}

			@Override
			public void render(Canvas c, float x, float y, float w, float h, float mx, float my) {
				float s = c.defaultFontSize() * 0.85f;
				c.text(FontFamily.SANS, s, wm.layoutName(wm.activeWorkspace()), x, y + (h - c.textHeight(FontFamily.SANS, s)) / 2, theme().textDim.argb());
			}
		});

		ctx.registerBarWidget(new BarWidget(ctx.id("window_title"), "Window Title", BarWidget.Side.CENTER, 0) {
			private String title() {
				return wm.focused().map(win -> {
					String sub = win.panel().subtitle();
					return sub == null || sub.isEmpty() ? win.panel().title() : win.panel().title() + " · " + sub;
				}).orElse("");
			}

			@Override
			public float width(Canvas c, float h) {
				return Math.min(200, c.textWidth(FontFamily.SANS_BOLD, c.defaultFontSize(), title()));
			}

			@Override
			public void render(Canvas c, float x, float y, float w, float h, float mx, float my) {
				String t = c.ellipsize(FontFamily.SANS_BOLD, c.defaultFontSize(), title(), w);
				c.text(FontFamily.SANS_BOLD, c.defaultFontSize(), t, x, y + (h - c.textHeight(FontFamily.SANS_BOLD, c.defaultFontSize())) / 2, theme().text.argb());
			}
		});

		ctx.registerBarWidget(new BarWidget(ctx.id("clock"), "Clock", BarWidget.Side.RIGHT, 100) {
			private final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");

			@Override
			public float width(Canvas c, float h) {
				return c.textWidth(FontFamily.SANS_BOLD, c.defaultFontSize(), "00:00") + 12;
			}

			@Override
			public void render(Canvas c, float x, float y, float w, float h, float mx, float my) {
				float ty = y + (h - c.textHeight()) / 2;
				c.text(FontFamily.MONO, c.defaultFontSize(), "", x, ty, theme().accent.argb());
				c.text(FontFamily.SANS_BOLD, c.defaultFontSize(), LocalTime.now().format(fmt), x + 11, ty, theme().text.argb());
			}
		});

		ctx.registerBarWidget(new BarWidget(ctx.id("updates"), "Updates", BarWidget.Side.RIGHT, 80) {
			private final UpdateManager updates = MyriadImpl.get().updates();

			private String text() {
				int n = updates.updatable().size();
				return n == 0 ? "" : String.valueOf(n);
			}

			@Override
			public float width(Canvas c, float h) {
				String t = text();
				return t.isEmpty() ? 0 : c.textWidth(FontFamily.SANS_BOLD, c.defaultFontSize(), t) + 12;
			}

			@Override
			public void render(Canvas c, float x, float y, float w, float h, float mx, float my) {
				boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
				float ty = y + (h - c.textHeight()) / 2;
				c.text(FontFamily.MONO, c.defaultFontSize(), "", x, ty, hover ? theme().text.argb() : theme().accent.argb());
				c.text(FontFamily.SANS_BOLD, c.defaultFontSize(), text(), x + 11, ty, theme().text.argb());
			}

			@Override
			public boolean mouseClicked(float mx, float my, int button) {
				wm.openPanel(CorePanels.ADDONS);
				return true;
			}
		});

		ctx.registerBarWidget(new BarWidget(ctx.id("fps"), "FPS", BarWidget.Side.RIGHT, 90) {
			private String text() {
				return Minecraft.getInstance().getFps() + " fps";
			}

			@Override
			public float width(Canvas c, float h) {
				return c.textWidth(FontFamily.SANS, c.defaultFontSize() * 0.9f, text());
			}

			@Override
			public void render(Canvas c, float x, float y, float w, float h, float mx, float my) {
				float s = c.defaultFontSize() * 0.9f;
				c.text(FontFamily.SANS, s, text(), x, y + (h - c.textHeight(FontFamily.SANS, s)) / 2, theme().textDim.argb());
			}
		});
	}
}
