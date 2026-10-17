package dev.myriadclient.api.util;

import dev.myriadclient.api.Myriad;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Entity helpers for combat and render features: classifying entities the same way everywhere (so "hostiles" means
 * the same thing in every addon), friend checks, and render-time interpolation.
 */
public final class Entities {
	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	private Entities() {
	}

	public enum Kind {
		PLAYER, HOSTILE, PASSIVE, OTHER
	}

	/** Players; hostiles (anything vanilla treats as a monster, including phantoms, slimes, ghasts and shulkers); passives; the rest. */
	public static Kind kind(Entity e) {
		if (e instanceof Player) return Kind.PLAYER;
		MobCategory g = e.getType().getCategory();
		if (g == MobCategory.MONSTER || e instanceof Enemy) return Kind.HOSTILE;
		if (g == MobCategory.CREATURE || g == MobCategory.AMBIENT || g == MobCategory.WATER_CREATURE
			|| g == MobCategory.WATER_AMBIENT || g == MobCategory.UNDERGROUND_WATER_CREATURE || g == MobCategory.AXOLOTLS) return Kind.PASSIVE;
		return Kind.OTHER;
	}

	public static boolean isHostile(Entity e) {
		return kind(e) == Kind.HOSTILE;
	}

	public static boolean isPassive(Entity e) {
		return kind(e) == Kind.PASSIVE;
	}

	/** A player on the user's friends list. */
	public static boolean isFriend(Entity e) {
		return e instanceof Player p && Myriad.friends().isFriend(p);
	}

	/** Alive, not dying, and not the local player. */
	public static boolean isAliveTarget(Entity e) {
		return e != mc().player && e.isAlive() && !(e instanceof LivingEntity l && l.isDeadOrDying());
	}

	/** The entity's bounding box at render time (interpolated between ticks). */
	public static AABB lerpedBox(Entity e, float tickDelta) {
		double x = Mth.lerp(tickDelta, e.xOld, e.getX()) - e.getX();
		double y = Mth.lerp(tickDelta, e.yOld, e.getY()) - e.getY();
		double z = Mth.lerp(tickDelta, e.zOld, e.getZ()) - e.getZ();
		return e.getBoundingBox().move(x, y, z);
	}

	public static Vec3 lerpedCenter(Entity e, float tickDelta) {
		return lerpedBox(e, tickDelta).getCenter();
	}

	/**
	 * Where {@code e} will be in {@code ticks} ticks if it keeps moving as it did last tick (a straight-line guess;
	 * good enough to lead shots or place crystals where a player is going). Uses the observed movement rather than
	 * velocity, which the client doesn't know for other players.
	 */
	public static Vec3 predict(Entity e, int ticks) {
		Vec3 moved = e.position().subtract(e.xo, e.yo, e.zo);
		return e.position().add(moved.scale(ticks));
	}

	/** {@code player}'s latency from the tab list, or 0 if unknown. */
	public static int ping(Player player) {
		var handler = mc().getConnection();
		var entry = handler == null ? null : handler.getPlayerInfo(player.getUUID());
		return entry == null ? 0 : entry.getLatency();
	}

	/** {@code player}'s game mode from the tab list, or null if unknown. */
	public static @Nullable GameType gameMode(Player player) {
		var handler = mc().getConnection();
		var entry = handler == null ? null : handler.getPlayerInfo(player.getUUID());
		return entry == null ? null : entry.getGameMode();
	}

	/** Whether any entity matching {@code filter} overlaps {@code box} (e.g. would block a placement). */
	public static boolean intersects(AABB box, Predicate<Entity> filter) {
		var world = mc().level;
		if (world == null) return false;
		for (Entity e : world.getEntities((Entity) null, box, filter)) if (e.isAlive()) return true;
		return false;
	}

	/** Whether {@code pos} is inside the client's render distance (chunks there are loaded and drawn). */
	public static boolean inRenderDistance(BlockPos pos) {
		var player = mc().player;
		if (player == null) return false;
		int chunks = mc().options.getEffectiveRenderDistance();
		return Math.abs((pos.getX() >> 4) - player.chunkPosition().x()) <= chunks && Math.abs((pos.getZ() >> 4) - player.chunkPosition().z()) <= chunks;
	}

	/** {@link #predict} applied to the bounding box. */
	public static AABB predictBox(Entity e, int ticks) {
		Vec3 offset = predict(e, ticks).subtract(e.position());
		return e.getBoundingBox().move(offset);
	}
}
