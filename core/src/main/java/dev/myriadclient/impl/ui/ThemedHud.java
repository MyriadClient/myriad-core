package dev.myriadclient.impl.ui;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.render.UiRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.function.Consumer;

/**
 * The hotbar, the off hand's slot and the experience bar drawn in the theme while Theme → Preferences → Themed Hotbar
 * is on: each sprite Minecraft draws for them is swapped for the theme's own shapes in the same place, so its items,
 * counts and the bars' sizes stay Minecraft's.
 */
public final class ThemedHud {
	/** A hotbar cell around its 16-pixel item; hotbar items are 20 apart, so two pixels between. */
	private static final float CELL = 18;

	private ThemedHud() {
	}

	private static boolean on() {
		return Myriad.isReady() && theme().themedHotbar.get();
	}

	private static ThemeSettings theme() {
		return MyriadImpl.get().windowManager().theme();
	}

	/** One of the hotbar's sprites at {@code x, y}, as Minecraft places it; false to let Minecraft draw it. */
	public static boolean hotbar(GuiGraphicsExtractor ctx, Identifier sprite, int x, int y) {
		if (!on()) return false;
		return switch (sprite.getPath()) {
			case "hud/hotbar" -> paint(ctx, c -> {
				ThemeSettings t = theme();
				window(c, t, x, y, 182, 22);
				// Its nine items start 3 pixels in and down.
				for (int i = 0; i < 9; i++) c.roundRect(x + 2 + i * 20, y + 2, CELL, CELL, cellRounding(t), t.surface.argb());
			});
			// 24 by 23, one pixel up and left of the slot's 22-pixel frame.
			case "hud/hotbar_selection" -> paint(ctx, c -> {
				ThemeSettings t = theme();
				float r = cellRounding(t);
				c.roundRect(x + 3, y + 3, CELL, CELL, r, ColorUtil.withAlpha(t.accent.argb(), 0x40));
				c.outline(x + 3, y + 3, CELL, CELL, r, 1, t.accent.argb());
			});
			// 29 by 24, the item 3 pixels in from its outer side (left) or 10 (right), 4 down.
			case "hud/hotbar_offhand_left", "hud/hotbar_offhand_right" -> paint(ctx, c -> {
				ThemeSettings t = theme();
				float item = x + (sprite.getPath().endsWith("left") ? 3 : 10);
				window(c, t, item - 3, y + 1, 22, 22);
				c.roundRect(item - 1, y + 3, CELL, CELL, cellRounding(t), t.surface.argb());
			});
			default -> false;
		};
	}

	/** The experience bar's track, where Minecraft puts it; false to let Minecraft draw it. */
	public static boolean experienceTrack(GuiGraphicsExtractor ctx, int x, int y, int w, int h) {
		if (!on()) return false;
		return paint(ctx, c -> {
			ThemeSettings t = theme();
			c.roundRect(x, y, w, h, h / 2f, ColorUtil.withAlpha(t.windowBackground.argb(), Math.max(0xC0, ColorUtil.alpha(t.windowBackground.argb()))));
			c.outline(x, y, w, h, h / 2f, 1, ColorUtil.withAlpha(t.inactiveBorder.argb(), 0x80));
		});
	}

	/** Its fill, {@code w} of the track's width; false to let Minecraft draw it. */
	public static boolean experienceFill(GuiGraphicsExtractor ctx, int x, int y, int w, int h) {
		if (!on()) return false;
		if (w <= 0) return true;
		return paint(ctx, c -> {
			ThemeSettings t = theme();
			c.gradientRect(x, y, Math.max(w, h), h, h / 2f, t.activeBorderFrom.argb(), t.activeBorderTo.argb(), 0);
		});
	}

	/** The experience level over the bar, in the theme's font and accent; false to let Minecraft draw it. */
	public static boolean level(GuiGraphicsExtractor ctx, int level) {
		if (!on()) return false;
		return paint(ctx, c -> {
			ThemeSettings t = theme();
			String text = Integer.toString(level);
			float size = c.defaultFontSize(), w = c.textWidth(FontFamily.SANS_BOLD, size, text);
			// Where Minecraft centres it: over the bar, 35 pixels up from the bottom.
			float x = ctx.guiWidth() / 2f - w / 2, y = ctx.guiHeight() - 35;
			c.text(FontFamily.SANS_BOLD, size, text, x + 0.75f, y + 0.75f, 0xC0000000);
			c.text(FontFamily.SANS_BOLD, size, text, x, y, t.accent.argb());
		});
	}

	/** A small window's surface and border, as the desktop draws its windows. */
	private static void window(UiRenderer c, ThemeSettings t, float x, float y, float w, float h) {
		float r = Math.min(t.rounding.get(), h / 2), b = t.borderSize.get();
		c.roundRect(x, y, w, h, r, ColorUtil.withAlpha(t.windowBackground.argb(), Math.max(0xC0, ColorUtil.alpha(t.windowBackground.argb()))));
		if (b > 0) c.gradientOutline(x, y, w, h, r, b, t.activeBorderFrom.argb(), t.activeBorderTo.argb(), t.borderAngle.get().floatValue());
	}

	private static float cellRounding(ThemeSettings t) {
		return Math.min(t.rounding.get(), 3);
	}

	private static boolean paint(GuiGraphicsExtractor ctx, Consumer<UiRenderer> draw) {
		MyriadImpl.get().windowManager().painter().paintGui(ctx, draw);
		return true;
	}
}
