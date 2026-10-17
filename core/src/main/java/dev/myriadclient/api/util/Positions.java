package dev.myriadclient.api.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Block positions around a point: the loops every placing, breaking and scanning feature needs. Results are
 * immutable {@link BlockPos}es, nearest first unless noted, so "do the closest one" is just the first element.
 */
public final class Positions {
	private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

	private Positions() {
	}

	/** Every block whose centre is within {@code radius} of {@code center}, nearest first. */
	public static List<BlockPos> sphere(Vec3 center, double radius) {
		return sphere(center, radius, p -> true);
	}

	/** Like {@link #sphere(Vec3, double)}, keeping only positions that pass {@code filter}. */
	public static List<BlockPos> sphere(Vec3 center, double radius, Predicate<BlockPos> filter) {
		List<BlockPos> out = new ArrayList<>();
		int r = (int) Math.ceil(radius);
		BlockPos origin = BlockPos.containing(center);
		double max = radius * radius;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int x = -r; x <= r; x++) {
			for (int y = -r; y <= r; y++) {
				for (int z = -r; z <= r; z++) {
					m.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
					if (Vec3.atCenterOf(m).distanceToSqr(center) <= max && filter.test(m)) out.add(m.immutable());
				}
			}
		}
		out.sort(nearest(center));
		return out;
	}

	/** Every block in the cube {@code radius} blocks out from {@code center} in each direction, nearest first. */
	public static List<BlockPos> cube(BlockPos center, int radius) {
		return box(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius), Vec3.atCenterOf(center));
	}

	/** Every block between two corners (inclusive), nearest to {@code sortFrom} first (or in order if null). */
	public static List<BlockPos> box(BlockPos a, BlockPos b, Vec3 sortFrom) {
		List<BlockPos> out = new ArrayList<>();
		for (BlockPos p : BlockPos.betweenClosed(a, b)) out.add(p.immutable());
		if (sortFrom != null) out.sort(nearest(sortFrom));
		return out;
	}

	public static Comparator<BlockPos> nearest(Vec3 to) {
		return Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(to));
	}

	/** The four positions beside {@code pos} (north, east, south, west). */
	public static List<BlockPos> horizontalNeighbours(BlockPos pos) {
		List<BlockPos> out = new ArrayList<>(4);
		for (Direction d : HORIZONTAL) out.add(pos.relative(d));
		return out;
	}

	/** The six positions touching {@code pos}. */
	public static List<BlockPos> neighbours(BlockPos pos) {
		List<BlockPos> out = new ArrayList<>(6);
		for (Direction d : Direction.values()) out.add(pos.relative(d));
		return out;
	}

	/** The middle of one face of the block at {@code pos}. */
	public static Vec3 faceCenter(BlockPos pos, Direction face) {
		return Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
	}
}
