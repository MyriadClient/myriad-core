package dev.myriadclient.impl.ui.bar;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.AddonContext;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.ui.BarWidget;
import dev.myriadclient.api.ui.Desktop;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.api.render.MyriadLogo;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.ui.WindowManager;
import dev.myriadclient.impl.ui.panels.CorePanels;
import dev.myriadclient.impl.update.UpdateManager;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import net.minecraft.client.Minecraft;

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
			/** Always shown; the rest appear while they have windows (or you're on them), like Omarchy's bar. */
			private static final int ALWAYS = 4;

			/** The workspaces to show, in order, ending with the HUD (0). */
			private int[] shown() {
				int active = wm.activeWorkspace();
				int[] out = new int[Desktop.WORKSPACES + 1];
				int n = 0;
				for (int ws = 1; ws <= Desktop.WORKSPACES; ws++) {
					if (ws <= ALWAYS || ws == active || wm.windowCount(ws) > 0) out[n++] = ws;
				}
				out[n++] = Desktop.HUD_WORKSPACE;
				return java.util.Arrays.copyOf(out, n);
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
					float cx = x + i * CELL + (ws == Desktop.HUD_WORKSPACE ? 2 : 0);
					boolean isActive = ws == active;
					boolean occupied = wm.windowCount(ws) > 0;
					boolean hover = mx >= cx && mx < cx + CELL && my >= y && my < y + h;
					if (isActive) c.roundRect(cx + 1, y + 3, CELL - 2, h - 6, 3, ColorUtil.withAlpha(theme().accent.argb(), 200));
					else if (hover) c.roundRect(cx + 1, y + 3, CELL - 2, h - 6, 3, theme().surfaceHover.argb());
					String label = ws == Desktop.HUD_WORKSPACE ? "\uf108" : String.valueOf(ws);
					FontFamily f = ws == Desktop.HUD_WORKSPACE ? FontFamily.MONO : FontFamily.SANS_BOLD;
					int color = isActive ? 0xFF11111B : occupied ? theme().text.argb() : ColorUtil.withAlpha(theme().textDim.argb(), 140);
					float tw = c.textWidth(f, c.defaultFontSize() * 0.9f, label);
					c.text(f, c.defaultFontSize() * 0.9f, label, cx + (CELL - tw) / 2, y + (h - c.textHeight(f, c.defaultFontSize() * 0.9f)) / 2, color);
				}
			}

			@Override
			public boolean mouseClicked(float mx, float my, int button) {
				int[] shown = shown();
				int i = (int) (mx / CELL);
				if (i >= 0 && i < shown.length) wm.switchWorkspace(shown[i]);
				return true;
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
