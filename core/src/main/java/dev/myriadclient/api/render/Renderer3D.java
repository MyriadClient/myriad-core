package dev.myriadclient.api.render;

import dev.myriadclient.impl.render.WorldRenderQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shapes drawn this frame only. Call these from a {@code Render3DEvent} handler; everything queued is drawn once after
 * all handlers ran. Coordinates are absolute world coordinates.
 * <p>
 * These are shortcuts for {@code event.shapes()} (a {@link ShapeBuilder}). For shapes that rarely change, build them
 * once into a {@link WorldMesh} or a {@code ChunkCache} instead of every frame.
 */
public final class Renderer3D {
	public enum ShapeMode {
		FILL, LINES, BOTH
	}

	private Renderer3D() {
	}

	/** This frame's shape target, the same one {@code Render3DEvent.shapes()} returns. */
	public static ShapeBuilder shapes() {
		return WorldRenderQueue.INSTANCE;
	}

	/** A box with a translucent fill and/or outline. {@code throughWalls} draws it without depth testing. */
	public static void box(AABB box, int fillColor, int lineColor, ShapeMode mode, boolean throughWalls) {
		WorldRenderQueue.INSTANCE.box(box, fillColor, lineColor, mode, throughWalls);
	}

	public static void line(Vec3 from, Vec3 to, int color, boolean throughWalls) {
		WorldRenderQueue.INSTANCE.line(from, to, color, throughWalls);
	}

	public static void line(Vec3 from, Vec3 to, int fromColor, int toColor, boolean throughWalls) {
		WorldRenderQueue.INSTANCE.line(from, to, fromColor, toColor, throughWalls);
	}

	/** A line from the centre of the screen to {@code to}. */
	public static void tracer(Vec3 to, int color) {
		WorldRenderQueue.INSTANCE.tracer(to, color);
	}

	/** See {@link ShapeBuilder#blockShape(BlockPos, int, int, ShapeMode, boolean)}. */
	public static void blockShape(BlockPos pos, int fillColor, int lineColor, ShapeMode mode, boolean throughWalls) {
		WorldRenderQueue.INSTANCE.blockShape(pos, fillColor, lineColor, mode, throughWalls);
	}

	/** One face of the block at {@code pos}, filled (e.g. the side a placement will click). */
	public static void side(BlockPos pos, Direction face, int color, boolean throughWalls) {
		WorldRenderQueue.INSTANCE.side(pos, face, color, throughWalls);
	}

	/** A flat ring around {@code center} (e.g. a range indicator at the player's feet). */
	public static void circle(Vec3 center, double radius, int color, boolean throughWalls) {
		WorldRenderQueue.INSTANCE.circle(center, radius, color, throughWalls);
	}

	/** Line width in pixels for the lines queued after this call. Resets to the default (2) every frame. */
	public static void lineWidth(float width) {
		WorldRenderQueue.INSTANCE.lineWidth(width);
	}
}
