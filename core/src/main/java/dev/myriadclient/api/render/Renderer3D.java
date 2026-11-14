package dev.myriadclient.api.render;

import dev.myriadclient.api.Myriad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shapes drawn this frame only. Call these from a {@code Render3DEvent} handler; everything queued is drawn once after
 * all handlers ran. Coordinates are absolute world coordinates.
 * <p>
 * These are shortcuts for {@code event.shapes()} (a {@link ShapeBuilder}). For shapes that rarely change, build them
 * once into a {@link WorldMesh} or a {@code ChunkCache} instead of every frame. Colours are ARGB ints; give fills a
 * low alpha ({@code 0x40...}) so the world shows through.
 *
 * <pre>{@code
 * @Subscribe
 * private void onRender(Render3DEvent e) {
 *     for (Entity t : targets.get()) {   // a TickCached list, worked out once a tick
 *         AABB box = Entities.lerpedBox(t, e.tickDelta());
 *         Renderer3D.box(box, 0x40FF4040, 0xFFFF4040, Renderer3D.ShapeMode.BOTH, true);
 *     }
 *     Renderer3D.circle(mc.player.position(), range.get(), 0x8040A0FF, false);
 * }
 * }</pre>
 */
public final class Renderer3D {
	/** Which parts of a shape are drawn. */
	public enum ShapeMode {
		/** Only the translucent faces. */
		FILL,
		/** Only the outline. */
		LINES,
		/** Faces and outline. */
		BOTH
	}

	private Renderer3D() {
	}

	/** This frame's shape target, the same one {@code Render3DEvent.shapes()} returns. */
	public static ShapeBuilder shapes() {
		return Myriad.render().shapes();
	}

	/** A box with a translucent fill and/or outline. {@code throughWalls} draws it without depth testing. */
	public static void box(AABB box, int fillColor, int lineColor, ShapeMode mode, boolean throughWalls) {
		Myriad.render().shapes().box(box, fillColor, lineColor, mode, throughWalls);
	}

	/** A line between two points, in the current {@link #lineWidth}. */
	public static void line(Vec3 from, Vec3 to, int color, boolean throughWalls) {
		Myriad.render().shapes().line(from, to, color, throughWalls);
	}

	/** A line whose colour fades from {@code fromColor} to {@code toColor}. */
	public static void line(Vec3 from, Vec3 to, int fromColor, int toColor, boolean throughWalls) {
		Myriad.render().shapes().line(from, to, fromColor, toColor, throughWalls);
	}

	/** A line from the centre of the screen (where the crosshair is) to {@code to}, always drawn through walls. */
	public static void tracer(Vec3 to, int color) {
		Myriad.render().tracer(to, color);
	}

	/** See {@link ShapeBuilder#blockShape(BlockPos, int, int, ShapeMode, boolean)}. */
	public static void blockShape(BlockPos pos, int fillColor, int lineColor, ShapeMode mode, boolean throughWalls) {
		Myriad.render().shapes().blockShape(pos, fillColor, lineColor, mode, throughWalls);
	}

	/** One face of the block at {@code pos}, filled (e.g. the side a placement will click). */
	public static void side(BlockPos pos, Direction face, int color, boolean throughWalls) {
		Myriad.render().shapes().side(pos, face, color, throughWalls);
	}

	/** A flat ring around {@code center} (e.g. a range indicator at the player's feet). */
	public static void circle(Vec3 center, double radius, int color, boolean throughWalls) {
		Myriad.render().shapes().circle(center, radius, color, throughWalls);
	}

	/** Line width in pixels for the lines queued after this call. Resets to the default (2) every frame. */
	public static void lineWidth(float width) {
		Myriad.render().shapes().lineWidth(width);
	}
}
