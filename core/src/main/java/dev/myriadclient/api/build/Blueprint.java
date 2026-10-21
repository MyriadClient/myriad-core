package dev.myriadclient.api.build;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
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
 * Blueprint printer = Blueprint.of(schematicPositions);  // stored by chunk section
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
				Map<BlockPos, Target> merged = new LinkedHashMap<>();
				self.near(center, range, merged::put);
				other.near(center, range, merged::put);
				merged.forEach(action);
			}

			@Override
			public void forEach(BiConsumer<BlockPos, Target> action) {
				Map<BlockPos, Target> merged = new LinkedHashMap<>();
				self.forEach(merged::put);
				other.forEach(merged::put);
				merged.forEach(action);
			}
		};
	}

	/**
	 * Fixed positions, stored the way chunks store blocks (a 16x16x16 array for each section that has any), so asking
	 * for what's in reach visits only the positions in reach, a lookup each, however many there are in all.
	 */
	static Blueprint of(Map<BlockPos, Target> positions) {
		final class Section {
			final BlockPos[] pos = new BlockPos[4096];
			final Target[] target = new Target[4096];
		}
		Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
		for (Map.Entry<BlockPos, Target> e : positions.entrySet()) {
			BlockPos pos = e.getKey().immutable();
			Section section = sections.computeIfAbsent(SectionPos.asLong(pos), k -> new Section());
			int i = (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | (pos.getX() & 15);
			section.pos[i] = pos;
			section.target[i] = e.getValue();
		}
		return new Blueprint() {
			@Override
			public void near(Vec3 center, double range, BiConsumer<BlockPos, Target> action) {
				double r2 = range * range;
				int x0 = Mth.floor(center.x - range), x1 = Mth.floor(center.x + range);
				int y0 = Mth.floor(center.y - range), y1 = Mth.floor(center.y + range);
				int z0 = Mth.floor(center.z - range), z1 = Mth.floor(center.z + range);
				// Section by section, so each is looked up once; inside one, only the part of the cube in reach.
				for (int sx = x0 >> 4; sx <= x1 >> 4; sx++) {
					for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
						for (int sz = z0 >> 4; sz <= z1 >> 4; sz++) {
							Section section = sections.get(SectionPos.asLong(sx, sy, sz));
							if (section == null) continue;
							int ax = Math.max(x0, sx << 4), bx = Math.min(x1, (sx << 4) + 15);
							int ay = Math.max(y0, sy << 4), by = Math.min(y1, (sy << 4) + 15);
							int az = Math.max(z0, sz << 4), bz = Math.min(z1, (sz << 4) + 15);
							for (int y = ay; y <= by; y++) {
								double dy = y + 0.5 - center.y;
								for (int z = az; z <= bz; z++) {
									double dz = z + 0.5 - center.z, dyz = dy * dy + dz * dz;
									if (dyz > r2) continue;
									for (int x = ax; x <= bx; x++) {
										int i = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
										if (section.pos[i] == null) continue;
										double dx = x + 0.5 - center.x;
										if (dx * dx + dyz <= r2) action.accept(section.pos[i], section.target[i]);
									}
								}
							}
						}
					}
				}
			}

			@Override
			public void forEach(BiConsumer<BlockPos, Target> action) {
				for (Section section : sections.values()) {
					for (int i = 0; i < 4096; i++) if (section.pos[i] != null) action.accept(section.pos[i], section.target[i]);
				}
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
