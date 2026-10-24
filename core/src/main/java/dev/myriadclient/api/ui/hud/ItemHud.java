package dev.myriadclient.api.ui.hud;

import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.api.util.ItemInfo;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/** Durability bars, percentages and colours for HUD elements that show items, so they all look alike. */
public final class ItemHud {
	public enum Durability {
		BAR, PERCENT, BOTH, NONE,
		/** The uses left, as a number (1561 for a new diamond pickaxe). */
		@ApiStatus.AvailableSince("1.1")
		NUMBER
	}

	private ItemHud() {
	}

	public static float percent(ItemStack stack) {
		return ItemInfo.durabilityFraction(stack);
	}

	/** Red at 0%, yellow at 50%, green at 100%. */
	public static int durabilityColor(float percent) {
		return percent > 0.5f
			? ColorUtil.argb(255, (int) (255 * (1 - (percent - 0.5f) * 2)), 255, 0)
			: ColorUtil.argb(255, 255, (int) (255 * percent * 2), 0);
	}

	/** Whether {@code mode} draws text over the item (a percentage or the number of uses left). */
	public static boolean showsPercent(Durability mode) {
		return mode == Durability.PERCENT || mode == Durability.BOTH || mode == Durability.NUMBER;
	}

	/** Draws the bar under an item at (x, y) of the given size. */
	public static void bar(Canvas c, ItemStack stack, float x, float y, float size) {
		float p = percent(stack);
		float bw = size * 0.8f, bx = x + (size - bw) / 2, by = y + size - 2;
		c.roundRect(bx, by, bw, 1.25f, 0.6f, 0x99000000);
		c.roundRect(bx, by, Math.max(1, bw * p), 1.25f, 0.6f, durabilityColor(p));
	}

	/** Draws the percentage centred over an item at x, top at y. */
	public static void percentText(Canvas c, ItemStack stack, float x, float y, float size, float textSize, boolean shadow) {
		text(c, stack, Math.round(percent(stack) * 100) + "%", x, y, size, textSize, shadow);
	}

	/** Draws the uses left centred over an item at x, top at y, in the durability colour. */
	@ApiStatus.AvailableSince("1.1")
	public static void numberText(Canvas c, ItemStack stack, float x, float y, float size, float textSize, boolean shadow) {
		text(c, stack, String.valueOf(ItemInfo.durability(stack)), x, y, size, textSize, shadow);
	}

	/** Draws the text {@code mode} shows over an item ({@link Durability#NUMBER}'s uses left, otherwise the percentage). */
	@ApiStatus.AvailableSince("1.1")
	public static void durabilityText(Canvas c, Durability mode, ItemStack stack, float x, float y, float size, float textSize, boolean shadow) {
		if (mode == Durability.NUMBER) numberText(c, stack, x, y, size, textSize, shadow);
		else percentText(c, stack, x, y, size, textSize, shadow);
	}

	private static void text(Canvas c, ItemStack stack, String text, float x, float y, float size, float textSize, boolean shadow) {
		float p = percent(stack);
		float tw = c.textWidth(FontFamily.SANS, textSize, text);
		HudStyle.draw(c, FontFamily.SANS, textSize, text, x + (size - tw) / 2, y, durabilityColor(p), shadow);
	}
}
