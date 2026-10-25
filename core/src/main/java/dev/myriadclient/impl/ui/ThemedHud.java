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
 * counts and the bars' sizes stay Minecraft's. The hotbar and what sits on it (hearts, food, armour, the bars) are
 * moved up {@link #MARGIN} off the screen's edge (HudMixin).
 */
public final class ThemedHud {
	/** A hotbar cell around its 16-pixel item; hotbar items are 20 apart, so two pixels between. */
	private static final float CELL = 18;
	/** The theme icon font's heart and shield (Material Design) and drumstick (Font Awesome), at a size that fills a 9-pixel icon. */
	private static final String HEART = "\uDB80\uDED1", DRUMSTICK = "\uEEF9", SHIELD = "\uDB81\uDC98";
	private static final float ICON_SIZE = 10;
	/** GUI pixels between the hotbar and the bottom of the screen. */
	public static final int MARGIN = 3;
	/** The experience bar drawn thinner than Minecraft's, as the desktop's progress bars are: this far in from its top and bottom. */
	private static final float BAR_INSET = 1;

	private ThemedHud() {
	}

	public static boolean on() {
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
		return paint(ctx, c -> c.roundRect(x, y + BAR_INSET, w, h - 2 * BAR_INSET, (h - 2 * BAR_INSET) / 2f, ColorUtil.withAlpha(theme().textDim.argb(), 50)));
	}

	/** Its fill, {@code w} of the track's width; false to let Minecraft draw it. */
	public static boolean experienceFill(GuiGraphicsExtractor ctx, int x, int y, int w, int h) {
		if (!on()) return false;
		if (w <= 0) return true;
		return paint(ctx, c -> {
			ThemeSettings t = theme();
			float bh = h - 2 * BAR_INSET;
			c.gradientRect(x, y + BAR_INSET, Math.max(w, bh), bh, bh / 2, t.activeBorderFrom.argb(), t.activeBorderTo.argb(), 0);
		});
	}

	/**
	 * A heart, food or armour icon, from the name of the sprite Minecraft would draw at {@code x, y} (9 by 9): the theme
	 * font's own heart, drumstick and shield in the theme's colours (hearts its red, or by kind its green when poisoned,
	 * dim when withered, yellow for absorption, cyan when frozen; food the accent, the secondary under Hunger; armour the
	 * text),
	 * half of one by drawing the full icon over the empty one only on its left, lighter while it flashes. False to let
	 * Minecraft draw it (a vehicle's hearts, say).
	 */
	public static boolean statusIcon(GuiGraphicsExtractor ctx, Identifier sprite, int x, int y) {
		if (!on()) return false;
		String path = sprite.getPath();
		ThemeSettings th = theme();
		String icon;
		int color;
		if (path.startsWith("hud/heart/")) {
			String kind = path.substring("hud/heart/".length());
			if (kind.startsWith("vehicle")) return false;
			icon = HEART;
			color = kind.startsWith("container") ? 0 : kind.startsWith("poisoned") ? th.green.argb() : kind.startsWith("withered") ? th.textDim.argb()
				: kind.startsWith("absorbing") ? th.yellow.argb() : kind.startsWith("frozen") ? th.cyan.argb() : th.red.argb();
		} else if (path.startsWith("hud/food_")) {
			icon = DRUMSTICK;
			color = path.contains("empty") ? 0 : path.contains("hunger") ? th.secondary.argb() : th.accent.argb();
		} else if (path.startsWith("hud/armor_")) {
			icon = SHIELD;
			color = path.contains("empty") ? 0 : th.text.argb();
		} else return false;
		boolean half = path.contains("half"), blinking = path.contains("blinking");
		return paint(ctx, c -> {
			ThemeSettings t = theme();
			int full = color;
			// The empty icon: under a half one, and what a container or an empty slot is.
			if (color == 0 || half) glyph(c, icon, x, y, ColorUtil.withAlpha(t.textDim.argb(), 0x60));
			if (color == 0) return;
			if (blinking) full = ColorUtil.lerp(full, 0xFFFFFFFF, 0.45f);
			if (half) {
				c.push();
				c.clip(x, y - 2, 4.5f, 13);
				glyph(c, icon, x, y, full);
				c.pop();
			} else glyph(c, icon, x, y, full);
		});
	}

	/** {@code icon} centred in the 9-pixel square at {@code x, y}, with a faint shadow so it reads over the world. */
	private static void glyph(UiRenderer c, String icon, float x, float y, int color) {
		float w = c.textWidth(FontFamily.MONO, ICON_SIZE, icon), h = c.textHeight(FontFamily.MONO, ICON_SIZE);
		float gx = x + (9 - w) / 2, gy = y + (9 - h) / 2;
		if (ColorUtil.alpha(color) > 0x80) c.text(FontFamily.MONO, ICON_SIZE, icon, gx + 0.6f, gy + 0.6f, ColorUtil.withAlpha(0xFF000000, 0x90));
		c.text(FontFamily.MONO, ICON_SIZE, icon, gx, gy, color);
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
		// Where Minecraft's own drawing is moved to (the margin), which the renderer doesn't follow by itself.
		float dx = ctx.pose().m20(), dy = ctx.pose().m21();
		MyriadImpl.get().windowManager().painter().paintGui(ctx, c -> {
			c.translate(dx, dy);
			draw.accept(c);
		});
		return true;
	}
}
