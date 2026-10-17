package dev.myriadclient.api.render;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.render.Projection;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Labels pinned to points in the world (nametags, logout spots, item names), drawn on the 2D overlay. */
public final class WorldLabel {
	/** A piece of a label: text in a colour, or an item icon ({@link #item}). */
	public record Segment(String text, int color, ItemStack item) {
		public Segment(String text, int color) {
			this(text, color, ItemStack.EMPTY);
		}

		/** An item icon, drawn the height of the text (e.g. what a chest holds, what a player is holding). */
		public static Segment item(ItemStack stack) {
			return new Segment("", 0, stack);
		}

		boolean isItem() {
			return !item.isEmpty();
		}
	}

	/** Where a label ended up on screen, so callers can stack things above it. */
	public record Placed(float x, float y, float width, float height, float scale) {
	}

	public enum Background {
		NONE, PLAIN, ROUNDED
	}

	private static final float PAD_X = 3, PAD_Y = 1.5f, GAP = 3;

	private WorldLabel() {
	}

	/** Size multiplier that shrinks labels a little with distance (down to half size at 50+ blocks). */
	public static float distanceScale(Vec3 world) {
		double d = Projection.camera().distanceTo(world);
		return (float) Math.clamp(1.0 - d * 0.01, 0.5, 1.0);
	}

	/**
	 * Draws {@code segments} side by side, centred on {@code world} with the label's bottom edge there. Returns null
	 * when the point is behind the camera or off screen.
	 */
	public static @Nullable Placed draw(Canvas c, Vec3 world, float scale, List<Segment> segments, Background bg, int fill, int outline, boolean shadow) {
		Vec3 s = Projection.toScreen(world);
		if (s == null || !Projection.onScreen(s, 200)) return null;
		float size = c.defaultFontSize() * scale;
		float width = 0;
		float th = c.textHeight(FontFamily.SANS, size);
		float icon = th * 1.25f;
		for (int i = 0; i < segments.size(); i++) {
			Segment seg = segments.get(i);
			width += (seg.isItem() ? icon : c.textWidth(FontFamily.SANS, size, seg.text)) + (i > 0 ? GAP * scale : 0);
		}
		boolean hasItem = segments.stream().anyMatch(Segment::isItem);
		float w = width + PAD_X * 2 * scale, h = (hasItem ? icon : th) + PAD_Y * 2 * scale;
		float x = (float) s.x - w / 2, y = (float) s.y - h;
		if (bg != Background.NONE) {
			float r = bg == Background.ROUNDED ? Math.min(Myriad.ui().theme().rounding.get(), h / 2) : 0;
			c.roundRect(x, y, w, h, r, fill);
			if ((outline >>> 24) != 0) c.outline(x, y, w, h, r, 1, outline);
		}
		float cx = x + PAD_X * scale, ty = y + PAD_Y * scale + (hasItem ? (icon - th) / 2 : 0);
		for (Segment seg : segments) {
			if (seg.isItem()) {
				c.item(seg.item, cx, y + PAD_Y * scale, icon, true);
				cx += icon + GAP * scale;
				continue;
			}
			if (shadow) c.text(FontFamily.SANS, size, seg.text, cx + 0.6f, ty + 0.6f, 0x99000000);
			cx += c.text(FontFamily.SANS, size, seg.text, cx, ty, seg.color) + GAP * scale;
		}
		return new Placed(x, y, w, h, scale);
	}
}
