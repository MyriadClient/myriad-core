package dev.myriadclient.api.render;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Draws touching blocks of the same group as one shape: a wall of chests becomes one outline instead of a box per
 * chest. Each block keeps its own shape (a chest is inset and 14/16 tall), stretched to the edge of its block on each
 * side where a block of the same group touches it, so the shapes meet flush and still fit the hitboxes. Then the union
 * is drawn:
 * <ul>
 * <li>faces where nothing of the same group covers them, including the part of a face a shorter neighbour leaves
 * showing (the step where a stacked column rises above its neighbour);</li>
 * <li>lines where the surface folds or ends, but not where a flat surface carries on into the next block.</li>
 * </ul>
 * Neighbours come from the lookup, not just {@code cells}, so a shape that crosses into the next chunk joins up as long
 * as the lookup can see it there.
 *
 * <pre>{@code
 * MergedBoxes.draw(mesh, positions, new MergedBoxes.Lookup() {
 *     public int group(BlockPos pos) { return wanted(pos) ? color(pos) : 0; }
 *     public AABB shape(BlockPos pos) { return null; }   // the whole block
 * }, group -> fillColor(group), group -> group, throughWalls);
 * }</pre>
 */
public final class MergedBoxes {
	/** What's at a block. */
	public interface Lookup {
		/** Which group the block belongs to (blocks of one group merge), or 0 for nothing to draw. */
		int group(BlockPos pos);

		/** The part of the block it fills, in block coordinates (0-1); null for the whole block. */
		@Nullable AABB shape(BlockPos pos);
	}

	/** The colour for a group; 0 (or fully transparent) draws none. */
	@FunctionalInterface
	public interface Paint {
		int color(int group);
	}

	private static final double EPS = 1e-3, SAME = 1e-9;

	private final ShapeBuilder out;
	private final Lookup lookup;
	private final Paint fill, line;
	private final boolean throughWalls;
	private final Long2IntOpenHashMap groups = new Long2IntOpenHashMap();
	private final Long2ObjectOpenHashMap<AABB> boxes = new Long2ObjectOpenHashMap<>();
	private final Set<List<Double>> edges = new HashSet<>();

	private MergedBoxes(ShapeBuilder out, Lookup lookup, Paint fill, Paint line, boolean throughWalls) {
		this.out = out;
		this.lookup = lookup;
		this.fill = fill;
		this.line = line;
		this.throughWalls = throughWalls;
	}

	/**
	 * Draws the merged shape of {@code cells} into {@code out}: faces in {@code fill}'s colour and lines in
	 * {@code line}'s (either may return 0 to skip). Positions whose group is 0 are skipped.
	 */
	public static void draw(ShapeBuilder out, Iterable<BlockPos> cells, Lookup lookup, Paint fill, Paint line, boolean throughWalls) {
		MergedBoxes m = new MergedBoxes(out, lookup, fill, line, throughWalls);
		for (BlockPos cell : cells) m.cell(cell.immutable());
	}

	private int group(BlockPos pos) {
		long key = pos.asLong();
		if (groups.containsKey(key)) return groups.get(key);
		int g = lookup.group(pos);
		groups.put(key, g);
		return g;
	}

	/** The block's (stretched) box in world space, or null if nothing is drawn there. */
	private @Nullable AABB box(BlockPos pos) {
		long key = pos.asLong();
		if (boxes.containsKey(key)) return boxes.get(key);
		AABB b = null;
		int g = group(pos);
		if (g != 0) {
			AABB own = lookup.shape(pos);
			double[] min = {0, 0, 0}, max = {1, 1, 1};
			if (own != null) {
				min = new double[]{clamp(own.minX), clamp(own.minY), clamp(own.minZ)};
				max = new double[]{clamp(own.maxX), clamp(own.maxY), clamp(own.maxZ)};
			}
			for (Direction d : Direction.values()) {
				if (group(pos.relative(d)) != g) continue;
				int i = d.getAxis().ordinal();
				if (d.getAxisDirection() == Direction.AxisDirection.POSITIVE) max[i] = 1;
				else min[i] = 0;
			}
			b = new AABB(pos.getX() + min[0], pos.getY() + min[1], pos.getZ() + min[2], pos.getX() + max[0], pos.getY() + max[1], pos.getZ() + max[2]);
		}
		boxes.put(key, b);
		return b;
	}

	private static double clamp(double v) {
		return Math.clamp(v, 0, 1);
	}

	private boolean occupied(double x, double y, double z, int group) {
		BlockPos pos = BlockPos.containing(x, y, z);
		if (group(pos) != group) return false;
		AABB b = box(pos);
		return b != null && b.contains(x, y, z);
	}

	private void cell(BlockPos cell) {
		int g = group(cell);
		AABB b = box(cell);
		if (b == null) return;
		int fillColor = fill.color(g), lineColor = line.color(g);
		boolean fills = (fillColor >>> 24) != 0, lines = (lineColor >>> 24) != 0;
		for (Direction n : Direction.values()) {
			Direction.Axis ax = n.getAxis();
			Direction.Axis u = ax == Direction.Axis.X ? Direction.Axis.Y : Direction.Axis.X, v = ax == Direction.Axis.Z ? Direction.Axis.Y : Direction.Axis.Z;
			boolean positive = n.getAxisDirection() == Direction.AxisDirection.POSITIVE;
			double plane = positive ? b.max(ax) : b.min(ax);
			double[] rect = {b.min(u), b.max(u), b.min(v), b.max(v)};
			List<double[]> rects = List.of(rect);
			// A face on the block's edge may be partly covered by the neighbour's box.
			if (Math.abs(plane - (cell.get(ax) + (positive ? 1 : 0))) < SAME && group(cell.relative(n)) == g) {
				AABB nb = box(cell.relative(n));
				if (nb != null && Math.abs((positive ? nb.min(ax) : nb.max(ax)) - plane) < SAME) {
					rects = subtract(rect, new double[]{nb.min(u), nb.max(u), nb.min(v), nb.max(v)});
				}
			}
			for (double[] r : rects) {
				Vec3 p00 = point(ax, plane, u, r[0], v, r[2]), p10 = point(ax, plane, u, r[1], v, r[2]);
				Vec3 p11 = point(ax, plane, u, r[1], v, r[3]), p01 = point(ax, plane, u, r[0], v, r[3]);
				if (fills) out.quad(p00, p10, p11, p01, fillColor, throughWalls);
				if (lines) {
					edge(p00, p10, g, lineColor);
					edge(p01, p11, g, lineColor);
					edge(p00, p01, g, lineColor);
					edge(p10, p11, g, lineColor);
				}
			}
		}
	}

	/** Draws a line unless it lies inside a flat surface, judging from the four sides around its middle. */
	private void edge(Vec3 a, Vec3 b, int group, int color) {
		if (a.distanceToSqr(b) < SAME * SAME || !edges.add(key(a, b))) return;
		Vec3 m = a.add(b).scale(0.5);
		// Step off the line along the two axes across it.
		double[] p, q;
		if (Math.abs(a.x - b.x) > SAME) {
			p = new double[]{0, EPS, 0};
			q = new double[]{0, 0, EPS};
		} else if (Math.abs(a.y - b.y) > SAME) {
			p = new double[]{EPS, 0, 0};
			q = new double[]{0, 0, EPS};
		} else {
			p = new double[]{EPS, 0, 0};
			q = new double[]{0, EPS, 0};
		}
		boolean pp = occupied(m.x + p[0] + q[0], m.y + p[1] + q[1], m.z + p[2] + q[2], group);
		boolean pn = occupied(m.x + p[0] - q[0], m.y + p[1] - q[1], m.z + p[2] - q[2], group);
		boolean np = occupied(m.x - p[0] + q[0], m.y - p[1] + q[1], m.z - p[2] + q[2], group);
		boolean nn = occupied(m.x - p[0] - q[0], m.y - p[1] - q[1], m.z - p[2] - q[2], group);
		int count = (pp ? 1 : 0) + (pn ? 1 : 0) + (np ? 1 : 0) + (nn ? 1 : 0);
		// A corner (one or three sides filled) or two diagonal sides is an edge; two sides side by side is a flat surface.
		if (count == 1 || count == 3 || (count == 2 && pp == nn)) out.line(a, b, color, throughWalls);
	}

	/** The same line from either end. */
	private static List<Double> key(Vec3 a, Vec3 b) {
		boolean swap = a.x > b.x || (a.x == b.x && (a.y > b.y || (a.y == b.y && a.z > b.z)));
		Vec3 lo = swap ? b : a, hi = swap ? a : b;
		return List.of(lo.x, lo.y, lo.z, hi.x, hi.y, hi.z);
	}

	private static Vec3 point(Direction.Axis ax, double plane, Direction.Axis u, double uv, Direction.Axis v, double vv) {
		double[] c = new double[3];
		c[ax.ordinal()] = plane;
		c[u.ordinal()] = uv;
		c[v.ordinal()] = vv;
		return new Vec3(c[0], c[1], c[2]);
	}

	/** {@code r} minus {@code s} (both {u0, u1, v0, v1}), as up to four rectangles. */
	private static List<double[]> subtract(double[] r, double[] s) {
		double u0 = Math.max(r[0], s[0]), u1 = Math.min(r[1], s[1]), v0 = Math.max(r[2], s[2]), v1 = Math.min(r[3], s[3]);
		if (u1 - u0 < SAME || v1 - v0 < SAME) return List.of(r);
		List<double[]> rest = new ArrayList<>(4);
		if (u0 - r[0] > SAME) rest.add(new double[]{r[0], u0, r[2], r[3]});
		if (r[1] - u1 > SAME) rest.add(new double[]{u1, r[1], r[2], r[3]});
		if (v0 - r[2] > SAME) rest.add(new double[]{u0, u1, r[2], v0});
		if (r[3] - v1 > SAME) rest.add(new double[]{u0, u1, v1, r[3]});
		return rest;
	}
}
