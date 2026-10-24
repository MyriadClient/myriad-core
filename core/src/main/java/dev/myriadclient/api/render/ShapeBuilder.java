package dev.myriadclient.api.render;

import dev.myriadclient.api.event.events.Render3DEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The shapes Myriad can draw in the world, in absolute world coordinates. The same calls work on both kinds of
 * target:
 * <ul>
 * <li>{@link Render3DEvent#shapes()}: drawn this frame only. For things that move every frame (entities, the
 * crosshair target).</li>
 * <li>{@link MeshBuilder} (from {@link WorldMesh#build} or a {@code ChunkCache} mesher): kept on the GPU and drawn every
 * frame until rebuilt. For things that rarely change (blocks, holes, storage), which then cost almost nothing per
 * frame.</li>
 * </ul>
 * Colours are ARGB. {@code throughWalls} draws without depth testing.
 *
 * @see Render3DEvent
 */
public abstract class ShapeBuilder {
	public static final float DEFAULT_LINE_WIDTH = 2f;

	/** Positions are stored relative to this point, subtracted in doubles so they stay precise far from spawn. */
	protected double originX, originY, originZ;
	protected float lineWidth = DEFAULT_LINE_WIDTH;

	protected ShapeBuilder() {
	}

	/** Line width in pixels for the lines added after this call. */
	public ShapeBuilder lineWidth(float width) {
		this.lineWidth = width;
		return this;
	}

	public float lineWidth() {
		return lineWidth;
	}

	// ---- boxes -----------------------------------------------------------------------------------------------------

	/** A box with a translucent fill and/or outline. */
	public final void box(AABB box, int fillColor, int lineColor, Renderer3D.ShapeMode mode, boolean throughWalls) {
		if (mode != Renderer3D.ShapeMode.LINES) boxFill(box, fillColor, throughWalls);
		if (mode != Renderer3D.ShapeMode.FILL) boxLines(box, lineColor, throughWalls);
	}

	/** The six faces of a box. Skipped when the colour is fully transparent. */
	public final void boxFill(AABB b, int color, boolean throughWalls) {
		if ((color >>> 24) == 0) return;
		float x0 = (float) (b.minX - originX), y0 = (float) (b.minY - originY), z0 = (float) (b.minZ - originZ);
		float x1 = (float) (b.maxX - originX), y1 = (float) (b.maxY - originY), z1 = (float) (b.maxZ - originZ);
		emitQuad(throughWalls, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, color);
		emitQuad(throughWalls, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, color);
		emitQuad(throughWalls, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, color);
		emitQuad(throughWalls, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, color);
		emitQuad(throughWalls, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, color);
		emitQuad(throughWalls, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, color);
	}

	/** The twelve edges of a box. Skipped when the colour is fully transparent. */
	public final void boxLines(AABB b, int color, boolean throughWalls) {
		if ((color >>> 24) == 0) return;
		float x0 = (float) (b.minX - originX), y0 = (float) (b.minY - originY), z0 = (float) (b.minZ - originZ);
		float x1 = (float) (b.maxX - originX), y1 = (float) (b.maxY - originY), z1 = (float) (b.maxZ - originZ);
		float w = lineWidth;
		emitLine(throughWalls, x0, y0, z0, x1, y0, z0, color, color, w);
		emitLine(throughWalls, x1, y0, z0, x1, y0, z1, color, color, w);
		emitLine(throughWalls, x1, y0, z1, x0, y0, z1, color, color, w);
		emitLine(throughWalls, x0, y0, z1, x0, y0, z0, color, color, w);
		emitLine(throughWalls, x0, y1, z0, x1, y1, z0, color, color, w);
		emitLine(throughWalls, x1, y1, z0, x1, y1, z1, color, color, w);
		emitLine(throughWalls, x1, y1, z1, x0, y1, z1, color, color, w);
		emitLine(throughWalls, x0, y1, z1, x0, y1, z0, color, color, w);
		emitLine(throughWalls, x0, y0, z0, x0, y1, z0, color, color, w);
		emitLine(throughWalls, x1, y0, z0, x1, y1, z0, color, color, w);
		emitLine(throughWalls, x1, y0, z1, x1, y1, z1, color, color, w);
		emitLine(throughWalls, x0, y0, z1, x0, y1, z1, color, color, w);
	}

	// ---- lines and quads -------------------------------------------------------------------------------------------

	public final void line(Vec3 from, Vec3 to, int color, boolean throughWalls) {
		line(from.x, from.y, from.z, to.x, to.y, to.z, color, color, throughWalls);
	}

	/** A line fading from {@code fromColor} to {@code toColor}. */
	public final void line(Vec3 from, Vec3 to, int fromColor, int toColor, boolean throughWalls) {
		line(from.x, from.y, from.z, to.x, to.y, to.z, fromColor, toColor, throughWalls);
	}

	public final void line(double x1, double y1, double z1, double x2, double y2, double z2, int fromColor, int toColor, boolean throughWalls) {
		emitLine(throughWalls, (float) (x1 - originX), (float) (y1 - originY), (float) (z1 - originZ),
			(float) (x2 - originX), (float) (y2 - originY), (float) (z2 - originZ), fromColor, toColor, lineWidth);
	}

	/** A filled quad through four corners, in order around its edge; seen from both sides. */
	public final void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color, boolean throughWalls) {
		float ax = (float) (a.x - originX), ay = (float) (a.y - originY), az = (float) (a.z - originZ);
		float bx = (float) (b.x - originX), by = (float) (b.y - originY), bz = (float) (b.z - originZ);
		float cx = (float) (c.x - originX), cy = (float) (c.y - originY), cz = (float) (c.z - originZ);
		float dx = (float) (d.x - originX), dy = (float) (d.y - originY), dz = (float) (d.z - originZ);
		emitQuad(throughWalls, ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, color);
		emitQuad(throughWalls, dx, dy, dz, cx, cy, cz, bx, by, bz, ax, ay, az, color);
	}

	/** One face through four corners, seen only from the side they run anticlockwise around ({@link #emitQuad}). */
	final void face(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color, boolean throughWalls) {
		emitQuad(throughWalls,
			(float) (a.x - originX), (float) (a.y - originY), (float) (a.z - originZ),
			(float) (b.x - originX), (float) (b.y - originY), (float) (b.z - originZ),
			(float) (c.x - originX), (float) (c.y - originY), (float) (c.z - originZ),
			(float) (d.x - originX), (float) (d.y - originY), (float) (d.z - originZ), color);
	}

	// ---- blocks ----------------------------------------------------------------------------------------------------

	/**
	 * The block at {@code pos} as its real shape (slabs, stairs, chests, fences), not a full cube: filled with its
	 * boxes and outlined along its edges. Draws a full cube for air, so a block that's about to be placed shows too.
	 */
	public final void blockShape(BlockPos pos, int fillColor, int lineColor, Renderer3D.ShapeMode mode, boolean throughWalls) {
		Minecraft mc = Minecraft.getInstance();
		blockShape(pos, mc.level == null ? null : mc.level.getBlockState(pos), fillColor, lineColor, mode, throughWalls);
	}

	/** Like {@link #blockShape(BlockPos, int, int, Renderer3D.ShapeMode, boolean)} with a state you already have. */
	public final void blockShape(BlockPos pos, BlockState state, int fillColor, int lineColor, Renderer3D.ShapeMode mode, boolean throughWalls) {
		Minecraft mc = Minecraft.getInstance();
		VoxelShape shape = state == null || mc.level == null ? Shapes.block() : state.getShape(mc.level, pos);
		shape(shape, pos.getX(), pos.getY(), pos.getZ(), fillColor, lineColor, mode, throughWalls);
	}

	/** A voxel shape (block-local, 0..1) placed at {@code x, y, z}: its boxes filled and its edges outlined. */
	public final void shape(VoxelShape shape, double x, double y, double z, int fillColor, int lineColor, Renderer3D.ShapeMode mode, boolean throughWalls) {
		if (shape.isEmpty()) shape = Shapes.block();
		if (mode != Renderer3D.ShapeMode.LINES && (fillColor >>> 24) != 0) {
			for (AABB b : shape.toAabbs()) boxFill(b.move(x, y, z), fillColor, throughWalls);
		}
		if (mode != Renderer3D.ShapeMode.FILL && (lineColor >>> 24) != 0) {
			float ox = (float) (x - originX), oy = (float) (y - originY), oz = (float) (z - originZ), w = lineWidth;
			shape.forAllEdges((x1, y1, z1, x2, y2, z2) ->
				emitLine(throughWalls, ox + (float) x1, oy + (float) y1, oz + (float) z1, ox + (float) x2, oy + (float) y2, oz + (float) z2, lineColor, lineColor, w));
		}
	}

	/** One face of the block at {@code pos}, filled (e.g. the side a placement will click). */
	public final void side(BlockPos pos, Direction face, int color, boolean throughWalls) {
		double t = 0.002;
		double x0 = pos.getX(), y0 = pos.getY(), z0 = pos.getZ(), x1 = x0 + 1, y1 = y0 + 1, z1 = z0 + 1;
		AABB b = switch (face) {
			case DOWN -> new AABB(x0, y0 - t, z0, x1, y0 + t, z1);
			case UP -> new AABB(x0, y1 - t, z0, x1, y1 + t, z1);
			case NORTH -> new AABB(x0, y0, z0 - t, x1, y1, z0 + t);
			case SOUTH -> new AABB(x0, y0, z1 - t, x1, y1, z1 + t);
			case WEST -> new AABB(x0 - t, y0, z0, x0 + t, y1, z1);
			case EAST -> new AABB(x1 - t, y0, z0, x1 + t, y1, z1);
		};
		boxFill(b, color, throughWalls);
	}

	/** A flat ring around {@code center} (e.g. a range indicator at the player's feet). */
	public final void circle(Vec3 center, double radius, int color, boolean throughWalls) {
		int segments = Math.clamp((int) (radius * 12), 24, 128);
		double px = center.x + radius, pz = center.z;
		for (int i = 1; i <= segments; i++) {
			double a = i * Math.PI * 2 / segments;
			double x = center.x + Math.cos(a) * radius, z = center.z + Math.sin(a) * radius;
			line(px, center.y, pz, x, center.y, z, color, color, throughWalls);
			px = x;
			pz = z;
		}
	}

	// ---- output ----------------------------------------------------------------------------------------------------

	/**
	 * A face with corners relative to the origin, seen only from the side its corners run anticlockwise around: a box's
	 * faces point out, so a see-through box shows its front faces and not its back ones through them.
	 */
	protected abstract void emitQuad(boolean throughWalls, float ax, float ay, float az, float bx, float by, float bz,
									 float cx, float cy, float cz, float dx, float dy, float dz, int color);

	/** A line with ends relative to the origin. */
	protected abstract void emitLine(boolean throughWalls, float ax, float ay, float az, float bx, float by, float bz,
									 int fromColor, int toColor, float width);
}
