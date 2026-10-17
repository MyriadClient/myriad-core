package dev.myriadclient.api.combat;

import dev.myriadclient.api.util.Positions;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

/**
 * Where end crystals can go, as the server decides it: on obsidian or bedrock, with air above and no entity in the
 * crystal's space. Pair it with {@link Damage#crystal} to score the spots.
 *
 * <pre>{@code
 * for (BlockPos base : Crystals.bases(Reach.eyes(), 4.5, false)) {
 *     float dmg = Damage.crystal(target, Crystals.position(base));
 *     ...
 * }
 * }</pre>
 *
 * {@code twoHigh} asks for the old (1.12) rule, two air blocks above the base, for servers that still use it.
 */
public final class Crystals {
	private Crystals() {
	}

	/** Obsidian or bedrock: what a crystal can be placed on. */
	public static boolean isBase(BlockState state) {
		return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
	}

	/** Whether a crystal can be placed on {@code base} now: a base block, air above, and nothing in the way. */
	public static boolean canPlace(BlockPos base, boolean twoHigh) {
		return canPlace(base, twoHigh, e -> false);
	}

	/**
	 * Like {@link #canPlace(BlockPos, boolean)}, not counting entities {@code ignore} accepts: a crystal you're breaking
	 * this tick, or items that will be gone by the time the placement arrives.
	 */
	public static boolean canPlace(BlockPos base, boolean twoHigh, Predicate<Entity> ignore) {
		var level = Minecraft.getInstance().level;
		if (level == null || !isBase(level.getBlockState(base))) return false;
		BlockPos above = base.above();
		if (!level.isEmptyBlock(above) || twoHigh && !level.isEmptyBlock(above.above())) return false;
		for (Entity e : level.getEntities((Entity) null, space(base), e -> !ignore.test(e))) {
			if (!e.isRemoved()) return false;
		}
		return true;
	}

	/** The space a crystal on {@code base} takes, which must be free of entities: two blocks tall above it. */
	public static AABB space(BlockPos base) {
		return new AABB(base.getX(), base.getY() + 1, base.getZ(), base.getX() + 1, base.getY() + 3, base.getZ() + 1);
	}

	/** Where a crystal placed on {@code base} sits (and explodes from): the middle of the top of the base. */
	public static Vec3 position(BlockPos base) {
		return new Vec3(base.getX() + 0.5, base.getY() + 1, base.getZ() + 0.5);
	}

	/** Every base within {@code range} of {@code from} a crystal could be placed on now, nearest first. */
	public static List<BlockPos> bases(Vec3 from, double range, boolean twoHigh) {
		return Positions.sphere(from, range, p -> canPlace(p, twoHigh));
	}

	public static boolean isCrystal(Entity e) {
		return e instanceof EndCrystal;
	}
}
