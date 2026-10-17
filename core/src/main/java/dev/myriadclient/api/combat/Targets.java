package dev.myriadclient.api.combat;

import dev.myriadclient.api.util.Entities;
import dev.myriadclient.api.util.MathUtil;
import dev.myriadclient.api.util.Reach;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * Finding entities to attack, aim at or highlight, with the same rules everywhere: what counts as a hostile, how
 * neutral mobs are treated, friends, invisibility, walls, and sorting. Most modules should use {@link TargetSettings},
 * which gives players the standard options and builds the query for you.
 *
 * <pre>{@code
 * Entity target = Targets.query().range(4.5).types(Targets.Type.PLAYERS, Targets.Type.HOSTILES).sort(Targets.Sort.HEALTH).best();
 * }</pre>
 */
public final class Targets {
	public enum Type {
		PLAYERS, HOSTILES, ANIMALS, VILLAGERS, BOSSES, PROJECTILES
	}

	public enum Sort {
		/** Closest first (to the nearest point of the hitbox). */
		DISTANCE,
		/** Lowest health (with absorption) first. */
		HEALTH,
		/** Smallest turn from where you're looking first. */
		ANGLE
	}

	private Targets() {
	}

	public static Query query() {
		return new Query();
	}

	/**
	 * What kind of target an entity is, or null for things that are never targets (items, orbs, the world).
	 * Neutral mobs (wolves, bees, endermen, zombified piglins) are hostiles while angry; when calm they're animals,
	 * or nothing at all with {@code onlyAngry}.
	 */
	public static @Nullable Type type(Entity e, boolean onlyAngry) {
		if (e instanceof Player) return Type.PLAYERS;
		if (e instanceof WitherBoss || e instanceof Warden || e instanceof EnderDragon) return Type.BOSSES;
		if (e instanceof Projectile) return Type.PROJECTILES;
		if (e instanceof AbstractVillager) return Type.VILLAGERS;
		if (e instanceof EnderMan enderman) return !onlyAngry || enderman.hasBeenStaredAt() ? Type.HOSTILES : null;
		if (e instanceof ZombifiedPiglin piglin) return !onlyAngry || piglin.isAggressive() ? Type.HOSTILES : null;
		if (e instanceof NeutralMob angerable && !(e instanceof Monster)) {
			if (angerable.isAngry()) return Type.HOSTILES;
			return onlyAngry ? null : Type.ANIMALS;
		}
		if (e instanceof Animal) return Type.ANIMALS;
		if (e instanceof Monster || e instanceof Enemy || Entities.isHostile(e)) return Type.HOSTILES;
		return null;
	}

	/** Health plus absorption; 0 for non-living entities. */
	public static float health(Entity e) {
		return e instanceof LivingEntity l ? l.getHealth() + l.getAbsorptionAmount() : 0;
	}

	public static final class Query {
		private double range = 6;
		private final Set<Type> types = EnumSet.of(Type.PLAYERS);
		private boolean onlyAngry, friends, invisibles = true, named = true, creative = true, throughWalls = true;
		private Sort sort = Sort.DISTANCE;
		private Predicate<Entity> filter = e -> true;

		private Query() {
		}

		/** Maximum distance from your eyes to the nearest point of the hitbox. */
		public Query range(double range) {
			this.range = range;
			return this;
		}

		/** Which kinds of entity count (players only by default). */
		public Query types(Type... types) {
			this.types.clear();
			this.types.addAll(List.of(types));
			return this;
		}

		public Query types(Set<Type> types) {
			this.types.clear();
			this.types.addAll(types);
			return this;
		}

		public Query onlyAngry(boolean onlyAngry) {
			this.onlyAngry = onlyAngry;
			return this;
		}

		/** Include friends (off by default). */
		public Query friends(boolean include) {
			this.friends = include;
			return this;
		}

		public Query invisibles(boolean include) {
			this.invisibles = include;
			return this;
		}

		/** Include mobs with name tags. */
		public Query named(boolean include) {
			this.named = include;
			return this;
		}

		/** Include players in creative mode. */
		public Query creative(boolean include) {
			this.creative = include;
			return this;
		}

		/** Include entities you can't see (on by default). */
		public Query throughWalls(boolean include) {
			this.throughWalls = include;
			return this;
		}

		public Query sort(Sort sort) {
			this.sort = sort;
			return this;
		}

		/** An extra condition of your own. */
		public Query filter(Predicate<Entity> filter) {
			this.filter = this.filter.and(filter);
			return this;
		}

		public boolean test(Entity e) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || e == mc.player || !e.isAlive() || !e.isAttackable() || e instanceof ExperienceOrb) return false;
			if (e instanceof LivingEntity l && l.isDeadOrDying()) return false;
			Type type = type(e, onlyAngry);
			if (type == null || !types.contains(type)) return false;
			if (!invisibles && e.isInvisible()) return false;
			if (!named && e.hasCustomName() && !(e instanceof Player)) return false;
			if (e instanceof Player p) {
				if (!friends && Entities.isFriend(p)) return false;
				if (!creative && p.isCreative()) return false;
			}
			if (MathUtil.distanceTo(e.getBoundingBox(), mc.player.getEyePosition()) > range) return false;
			if (!throughWalls && !mc.player.hasLineOfSight(e)) return false;
			return filter.test(e);
		}

		/** Every match, best first. */
		public List<Entity> list() {
			Minecraft mc = Minecraft.getInstance();
			List<Entity> out = new ArrayList<>();
			if (mc.level == null || mc.player == null) return out;
			for (Entity e : mc.level.entitiesForRendering()) if (test(e)) out.add(e);
			out.sort(comparator());
			return out;
		}

		/** The best match, or null. */
		public @Nullable Entity best() {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null || mc.player == null) return null;
			Comparator<Entity> order = comparator();
			Entity best = null;
			for (Entity e : mc.level.entitiesForRendering()) {
				if (test(e) && (best == null || order.compare(e, best) < 0)) best = e;
			}
			return best;
		}

		private Comparator<Entity> comparator() {
			Minecraft mc = Minecraft.getInstance();
			Vec3 eyes = Reach.eyes();
			Comparator<Entity> byDistance = Comparator.comparingDouble(e -> MathUtil.distanceTo(e.getBoundingBox(), eyes));
			return switch (sort) {
				case DISTANCE -> byDistance;
				case HEALTH -> Comparator.comparingDouble(Targets::health).thenComparing(byDistance);
				case ANGLE -> Comparator.comparingDouble((Entity e) -> MathUtil.angleTo(eyes, mc.player.getYRot(), mc.player.getXRot(), e.getBoundingBox().getCenter()))
					.thenComparing(byDistance);
			};
		}
	}
}
