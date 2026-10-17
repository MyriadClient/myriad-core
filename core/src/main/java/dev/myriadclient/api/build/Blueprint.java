package dev.myriadclient.api.build;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * The shape a build works towards: a {@link Target} for each position. The planner only looks at the part in reach
 * each tick, so a blueprint can be huge (a whole highway or schematic) and cost only what's around you.
 *
 * <pre>{@code
 * Blueprint tunnel = Blueprint.box(start, start.offset(2, 2, 10_000), Target.air());
 * Blueprint floor  = Blueprint.box(a, b, Target.solid());
 * Blueprint surround = Blueprint.dynamic(() -> {         // follows you; evaluated once per tick
 *     BlockPos feet = mc.player.blockPosition();
 *     Map<BlockPos, Target> m = new HashMap<>();
 *     for (Direction d : Direction.Plane.HORIZONTAL) m.put(feet.relative(d), Target.anyOf(Blocks.OBSIDIAN));
 *     return m;
 * });
 * Blueprint printer = Blueprint.of(schematicPositions);  // indexed by chunk section
 * }</pre>
 */
public interface Blueprint {
	/** Calls {@code action} for every position within {@code range} of {@code center}. */
	void near(Vec3 center, double range, BiConsumer<BlockPos, Target> action);

	/** Calls {@code action} for every position. Can be slow for large blueprints; the planner calls it rarely. */
	void forEach(BiConsumer<BlockPos, Target> action);

	/** This blueprint with {@code other} on top: where both have a position, {@code other}'s target counts. */
	default Blueprint and(Blueprint other) {
		Blueprint self = this;
		return new Blueprint() {
			@Override
			public void near(Vec3 center, double range, BiConsumer<BlockPos, Target> action) {
				Map<BlockPos, Target> merged = new java.util.LinkedHashMap<>();
				self.near(center, range, merged::put);
				other.near(center, range, merged::put);
				merged.forEach(action);
			}

			@Override
			public void forEach(BiConsumer<BlockPos, Target> action) {
				Map<BlockPos, Target> merged = new java.util.LinkedHashMap<>();
				self.forEach(merged::put);
				other.forEach(merged::put);
				merged.forEach(action);
			}
		};
	}

	/** Fixed positions, indexed by chunk section so looking up what's in reach stays cheap however many there are. */
	static Blueprint of(Map<BlockPos, Target> positions) {
		Long2ObjectOpenHashMap<List<Map.Entry<BlockPos, Target>>> sections = new Long2ObjectOpenHashMap<>();
		for (Map.Entry<BlockPos, Target> e : positions.entrySet()) {
			BlockPos pos = e.getKey().immutable();
			sections.computeIfAbsent(SectionPos.asLong(pos), k -> new ArrayList<>()).add(Map.entry(pos, e.getValue()));
		}
		return new Blueprint() {
			@Override
			public void near(Vec3 center, double range, BiConsumer<BlockPos, Target> action) {
				double r2 = range * range;
				int minX = SectionPos.blockToSectionCoord(Mth.floor(center.x - range)), maxX = SectionPos.blockToSectionCoord(Mth.floor(center.x + range));
				int minY = SectionPos.blockToSectionCoord(Mth.floor(center.y - range)), maxY = SectionPos.blockToSectionCoord(Mth.floor(center.y + range));
				int minZ = SectionPos.blockToSectionCoord(Mth.floor(center.z - range)), maxZ = SectionPos.blockToSectionCoord(Mth.floor(center.z + range));
				for (int sx = minX; sx <= maxX; sx++) {
					for (int sy = minY; sy <= maxY; sy++) {
						for (int sz = minZ; sz <= maxZ; sz++) {
							List<Map.Entry<BlockPos, Target>> list = sections.get(SectionPos.asLong(sx, sy, sz));
							if (list == null) continue;
							for (Map.Entry<BlockPos, Target> e : list) {
								if (Vec3.atCenterOf(e.getKey()).distanceToSqr(center) <= r2) action.accept(e.getKey(), e.getValue());
							}
						}
					}
				}
			}

			@Override
			public void forEach(BiConsumer<BlockPos, Target> action) {
				for (List<Map.Entry<BlockPos, Target>> list : sections.values()) for (Map.Entry<BlockPos, Target> e : list) action.accept(e.getKey(), e.getValue());
			}
		};
	}

	/** Every position in the box between two corners (inclusive), all with the same target. Costs nothing to store. */
	static Blueprint box(BlockPos a, BlockPos b, Target target) {
		BlockPos min = BlockPos.min(a, b), max = BlockPos.max(a, b);
		return new Blueprint() {
			@Override
			public void near(Vec3 center, double range, BiConsumer<BlockPos, Target> action) {
				double r2 = range * range;
				int x0 = Math.max(min.getX(), Mth.floor(center.x - range)), x1 = Math.min(max.getX(), Mth.floor(center.x + range));
				int y0 = Math.max(min.getY(), Mth.floor(center.y - range)), y1 = Math.min(max.getY(), Mth.floor(center.y + range));
				int z0 = Math.max(min.getZ(), Mth.floor(center.z - range)), z1 = Math.min(max.getZ(), Mth.floor(center.z + range));
				for (int x = x0; x <= x1; x++) {
					for (int y = y0; y <= y1; y++) {
						for (int z = z0; z <= z1; z++) {
							BlockPos pos = new BlockPos(x, y, z);
							if (Vec3.atCenterOf(pos).distanceToSqr(center) <= r2) action.accept(pos, target);
						}
					}
				}
			}

			@Override
			public void forEach(BiConsumer<BlockPos, Target> action) {
				for (BlockPos pos : BlockPos.betweenClosed(min, max)) action.accept(pos.immutable(), target);
			}
		};
	}

	/**
	 * Positions worked out again every tick, for shapes that follow you or react to the world (surround, scaffold).
	 * Keep {@code positions} small; it's called once per tick while the build runs.
	 */
	static Blueprint dynamic(Supplier<Map<BlockPos, Target>> positions) {
		return new Blueprint() {
			@Override
			public void near(Vec3 center, double range, BiConsumer<BlockPos, Target> action) {
				double r2 = range * range;
				positions.get().forEach((pos, target) -> {
					if (Vec3.atCenterOf(pos).distanceToSqr(center) <= r2) action.accept(pos, target);
				});
			}

			@Override
			public void forEach(BiConsumer<BlockPos, Target> action) {
				positions.get().forEach(action);
			}
		};
	}
}
