package dev.myriadclient.api.world;

import dev.myriadclient.api.util.BlockInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Holes and surrounds: the questions crystal PvP modules keep asking about the blocks around a player's feet. A hole is
 * a 1x1, 2x1 or 2x2 pocket of air with headroom, floored and walled by blocks that survive explosions, so crystals and
 * beds can't hit the feet of whoever stands in it. A surround is the ring of blocks around an entity's feet, and its
 * city blocks are the ones that can be mined to open it up.
 *
 * <pre>{@code
 * for (Holes.Hole h : Holes.around(mc.player.blockPosition(), 8)) draw(h.box(), h.safety() == Holes.Safety.BEDROCK ? green : yellow);
 * if (!Holes.isSurrounded(mc.player)) Myriad.building().start(this, Blueprint.of(...Holes.surround(mc.player)...), options);
 * List<BlockPos> mine = Holes.city(target);
 * }</pre>
 *
 * Methods without a {@link BlockGetter} read the client world. {@link #around} is a full scan each call (about 5,000
 * positions at radius 8): fine for a one-off, not per tick; a Hole ESP uses {@link #scan(LevelChunk)} from a
 * {@link ChunkCache} instead, which redoes a chunk only when a block in it changes.
 */
public final class Holes {
	private Holes() {
	}

	/** How safe a hole is. */
	public enum Safety {
		/** Every wall and the floor is unbreakable (bedrock): nobody can open it. */
		BEDROCK,
		/** Every wall and the floor survives explosions, but some can be mined (obsidian, ender chests). */
		BLAST_PROOF
	}

	public enum Shape {
		/** One block. */
		SINGLE,
		/** Two blocks side by side. */
		DOUBLE,
		/** Two by two. */
		QUAD
	}

	/**
	 * A hole. {@code origin} is its lowest-x, lowest-z cell; {@code axis} says which way a {@link Shape#DOUBLE} runs
	 * (null otherwise).
	 */
	public record Hole(BlockPos origin, Shape shape, Direction.@Nullable Axis axis, Safety safety) {
		/** The air blocks you stand in. */
		public List<BlockPos> cells() {
			return switch (shape) {
				case SINGLE -> List.of(origin);
				case DOUBLE -> List.of(origin, origin.relative(Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE)));
				case QUAD -> List.of(origin, origin.east(), origin.south(), origin.south().east());
			};
		}

		/** The cells as one box, one block high. */
		public AABB box() {
			int dx = shape == Shape.QUAD || axis == Direction.Axis.X ? 2 : 1;
			int dz = shape == Shape.QUAD || axis == Direction.Axis.Z ? 2 : 1;
			return new AABB(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + dx, origin.getY() + 1, origin.getZ() + dz);
		}

		/** Where to stand in it: the middle of its floor. */
		public Vec3 center() {
			AABB b = box();
			return new Vec3(b.getCenter().x, b.minY, b.getCenter().z);
		}

		public boolean contains(BlockPos pos) {
			return cells().contains(pos);
		}
	}

	private static BlockGetter world() {
		return Minecraft.getInstance().level;
	}

	/** The hole {@code pos} is a cell of (the air at foot level), or null. Single holes are found first, then doubles, then quads. */
	public static @Nullable Hole at(BlockPos pos) {
		BlockGetter level = world();
		return level == null ? null : at(level, pos);
	}

	public static @Nullable Hole at(BlockGetter level, BlockPos pos) {
		if (!isCell(level, pos)) return null;
		Hole h = check(level, pos, Shape.SINGLE, null);
		if (h != null) return h;
		for (Direction.Axis axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
			Direction step = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
			if ((h = check(level, pos, Shape.DOUBLE, axis)) != null) return h;
			if ((h = check(level, pos.relative(step.getOpposite()), Shape.DOUBLE, axis)) != null) return h;
		}
		for (int dx = 0; dx >= -1; dx--) {
			for (int dz = 0; dz >= -1; dz--) {
				if ((h = check(level, pos.offset(dx, 0, dz), Shape.QUAD, null)) != null) return h;
			}
		}
		return null;
	}

	/** Every hole with a cell within {@code radius} blocks (a cube) of {@code center}, nearest first, each once. */
	public static List<Hole> around(BlockPos center, int radius) {
		BlockGetter level = world();
		if (level == null) return List.of();
		Set<BlockPos> seen = new HashSet<>();
		List<Hole> found = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
			if (seen.contains(pos)) continue;
			Hole h = at(level, pos);
			if (h == null) continue;
			seen.addAll(h.cells());
			found.add(h);
		}
		Vec3 c = Vec3.atCenterOf(center);
		found.sort(Comparator.comparingDouble(h -> h.center().distanceToSqr(c)));
		return found;
	}

	/**
	 * Every hole whose origin cell is in {@code chunk}, each once, for a {@link ChunkCache} (use {@code .neighbours()},
	 * as walls can be in the chunk beside). Only cells whose floor is blast-proof are looked at, which the chunk's
	 * palettes answer for whole sections without reading a block.
	 */
	public static List<Hole> scan(LevelChunk chunk) {
		List<Hole> found = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		BlockGetter level = chunk.getLevel();
		BlockScan.forEach(chunk, BlockInfo::isBlastResistant, (pos, state) -> {
			BlockPos cell = pos.above();
			if (seen.contains(cell)) return;
			Hole h = at(level, cell);
			if (h == null || !h.origin().equals(cell) && !h.cells().contains(cell)) return;
			// Count the hole where its origin is, so a 2x2 across a chunk border is found by one chunk only.
			if (!h.origin().equals(cell)) {
				seen.addAll(h.cells());
				return;
			}
			seen.addAll(h.cells());
			found.add(h);
		});
		return found;
	}

	/** The hole {@code entity} stands in (its whole footprint inside one), or null. */
	public static @Nullable Hole holeOf(Entity entity) {
		BlockGetter level = entity.level();
		List<BlockPos> feet = footprint(entity);
		Hole h = at(level, feet.getFirst());
		return h != null && h.cells().containsAll(feet) ? h : null;
	}

	/** The blocks at foot level that {@code entity}'s bounding box overlaps (one to four for a player). */
	public static List<BlockPos> footprint(Entity entity) {
		AABB b = entity.getBoundingBox();
		int y = Mth.floor(b.minY + 0.2);
		List<BlockPos> out = new ArrayList<>(4);
		for (int x = Mth.floor(b.minX); x <= Mth.floor(b.maxX - 1e-6); x++) {
			for (int z = Mth.floor(b.minZ); z <= Mth.floor(b.maxZ - 1e-6); z++) out.add(new BlockPos(x, y, z));
		}
		return out;
	}

	/** The positions around {@code entity}'s feet that a surround fills: beside its footprint, not in it. */
	public static List<BlockPos> surround(Entity entity) {
		List<BlockPos> feet = footprint(entity);
		List<BlockPos> out = new ArrayList<>(8);
		for (BlockPos f : feet) {
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos n = f.relative(d);
				if (!feet.contains(n) && !out.contains(n)) out.add(n);
			}
		}
		return out;
	}

	/** Whether every {@link #surround} position of {@code entity} survives explosions. */
	public static boolean isSurrounded(Entity entity) {
		for (BlockPos p : surround(entity)) if (!BlockInfo.isBlastResistant(entity.level().getBlockState(p))) return false;
		return true;
	}

	/** {@code entity}'s surround blocks that survive explosions but can be mined: breaking one lets a crystal reach its feet. */
	public static List<BlockPos> city(Entity entity) {
		List<BlockPos> out = new ArrayList<>(4);
		for (BlockPos p : surround(entity)) {
			BlockState s = entity.level().getBlockState(p);
			if (BlockInfo.isBlastResistant(s) && !BlockInfo.isUnbreakable(s)) out.add(p);
		}
		return out;
	}

	/** Air you could stand in, with headroom, over a floor that survives explosions. */
	private static boolean isCell(BlockGetter level, BlockPos pos) {
		return isOpen(level, pos) && isOpen(level, pos.above()) && BlockInfo.isBlastResistant(level.getBlockState(pos.below()));
	}

	private static boolean isOpen(BlockGetter level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		return s.getCollisionShape(level, pos).isEmpty() && s.getFluidState().isEmpty();
	}

	private static @Nullable Hole check(BlockGetter level, BlockPos origin, Shape shape, Direction.@Nullable Axis axis) {
		Hole hole = new Hole(origin.immutable(), shape, axis, Safety.BEDROCK);
		List<BlockPos> cells = hole.cells();
		boolean bedrock = true;
		for (BlockPos c : cells) {
			if (!isCell(level, c)) return null;
			bedrock &= BlockInfo.isUnbreakable(level.getBlockState(c.below()));
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos n = c.relative(d);
				if (cells.contains(n)) continue;
				BlockState s = level.getBlockState(n);
				if (!BlockInfo.isBlastResistant(s)) return null;
				bedrock &= BlockInfo.isUnbreakable(s);
			}
		}
		return bedrock ? hole : new Hole(hole.origin(), shape, axis, Safety.BLAST_PROOF);
	}
}
