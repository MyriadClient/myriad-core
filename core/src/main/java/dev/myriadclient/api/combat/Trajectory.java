package dev.myriadclient.api.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.WindChargeItem;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Where a projectile goes, worked out tick by tick as the game moves it (gravity, drag, water), until it hits a block or
 * an entity. For trajectory previews, bow aim, pearl spots and dodging arrows.
 *
 * <pre>{@code
 * Trajectory.Launch launch = Trajectory.launch(mc.player, mc.player.getMainHandItem(), mc.player.getYRot(), mc.player.getXRot());
 * if (launch != null) {
 *     Trajectory.Path path = Trajectory.simulate(launch, 200, mc.player);
 *     path.points();   // one point per tick, to draw as a line
 *     path.hit();      // the block or entity it lands on, or null
 * }
 * }</pre>
 *
 * Each simulated tick raycasts against blocks and entities, so a path costs about as much as a hundred raycasts.
 * Simulating one path per frame is fine; several (every arrow in the air) should be simulated once per tick
 * ({@link dev.myriadclient.api.util.TickCached}) and drawn from that.
 */
public final class Trajectory {
	private Trajectory() {
	}

	/**
	 * How a kind of projectile flies, per tick: {@code gravity} pulls it down, {@code drag} (or {@code waterDrag} in
	 * water) scales its speed, and {@code radius} is how far from its centre it hits entities. Arrows move before drag
	 * and gravity apply ({@code movesFirst}); thrown items after.
	 */
	public record Ballistics(double gravity, double drag, double waterDrag, boolean movesFirst, double radius) {
		public static final Ballistics ARROW = new Ballistics(0.05, 0.99, 0.6, true, 0.25);
		public static final Ballistics TRIDENT = new Ballistics(0.05, 0.99, 0.99, true, 0.25);
		/** Snowballs, eggs and ender pearls. */
		public static final Ballistics THROWN = new Ballistics(0.03, 0.99, 0.8, false, 0.125);
		public static final Ballistics POTION = new Ballistics(0.05, 0.99, 0.8, false, 0.125);
		public static final Ballistics EXPERIENCE_BOTTLE = new Ballistics(0.07, 0.99, 0.8, false, 0.125);
		public static final Ballistics WIND_CHARGE = new Ballistics(0, 1, 1, false, 0.15625);
		/** A firework from a crossbow: straight ahead, no gravity. */
		public static final Ballistics FIREWORK = new Ballistics(0, 1, 1, false, 0.125);
	}

	/** A projectile leaving {@code origin} at {@code velocity} blocks per tick. */
	public record Launch(Vec3 origin, Vec3 velocity, Ballistics ballistics) {
	}

	/** The points a projectile passes, one per tick from its launch, and what it hits (null if nothing in time). */
	public record Path(List<Vec3> points, @Nullable HitResult hit) {
		/** Where it ends: the hit, or the last point simulated. */
		public Vec3 end() {
			return hit != null ? hit.getLocation() : points.getLast();
		}

		public @Nullable Entity hitEntity() {
			return hit instanceof EntityHitResult e ? e.getEntity() : null;
		}

		public @Nullable BlockPos hitBlock() {
			return hit instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b.getBlockPos() : null;
		}
	}

	/**
	 * How {@code shooter} would launch {@code item} facing {@code yaw}/{@code pitch}: bows at their current draw (full
	 * when not drawing), charged crossbows, tridents, snowballs, eggs, pearls, splash and lingering potions, bottles o'
	 * enchanting and wind charges. Null for anything else, or an uncharged crossbow.
	 */
	public static @Nullable Launch launch(LivingEntity shooter, ItemStack item, float yaw, float pitch) {
		Ballistics b;
		float power, pitchOffset = 0;
		double eyeOffset = -0.1;
		if (item.getItem() instanceof BowItem) {
			boolean drawing = shooter.isUsingItem() && shooter.getUseItem() == item;
			power = (drawing ? BowItem.getPowerForTime(shooter.getTicksUsingItem()) : 1) * 3;
			b = Ballistics.ARROW;
		} else if (item.getItem() instanceof CrossbowItem) {
			ChargedProjectiles charged = item.get(DataComponents.CHARGED_PROJECTILES);
			if (charged == null || charged.isEmpty()) return null;
			boolean firework = charged.contains(Items.FIREWORK_ROCKET);
			power = firework ? 1.6f : 3.15f;
			b = firework ? Ballistics.FIREWORK : Ballistics.ARROW;
		} else if (item.getItem() instanceof TridentItem) {
			power = 2.5f;
			b = Ballistics.TRIDENT;
		} else if (item.getItem() instanceof SnowballItem || item.getItem() instanceof EggItem || item.getItem() instanceof EnderpearlItem) {
			power = 1.5f;
			b = Ballistics.THROWN;
		} else if (item.getItem() instanceof ThrowablePotionItem) {
			power = 0.5f;
			pitchOffset = -20;
			b = Ballistics.POTION;
		} else if (item.getItem() instanceof ExperienceBottleItem) {
			power = 0.7f;
			pitchOffset = -20;
			b = Ballistics.EXPERIENCE_BOTTLE;
		} else if (item.getItem() instanceof WindChargeItem) {
			power = 1.5f;
			eyeOffset = 0;
			b = Ballistics.WIND_CHARGE;
		} else {
			return null;
		}
		// As Projectile.shootFromRotation: the look direction (with the item's pitch offset) times the power, plus the
		// shooter's own movement (vertical only while airborne).
		float r = Mth.DEG_TO_RAD;
		Vec3 dir = new Vec3(-Mth.sin(yaw * r) * Mth.cos(pitch * r), -Mth.sin((pitch + pitchOffset) * r), Mth.cos(yaw * r) * Mth.cos(pitch * r));
		Vec3 own = shooter.getKnownMovement();
		Vec3 velocity = dir.normalize().scale(power).add(own.x, shooter.onGround() ? 0 : own.y, own.z);
		return new Launch(new Vec3(shooter.getX(), shooter.getEyeY() + eyeOffset, shooter.getZ()), velocity, b);
	}

	/**
	 * The yaw and pitch to launch {@code item} at so it passes through {@code target}, on the low arc (the direct shot),
	 * ignoring blocks and entities in the way; or null if it can't reach that far. For bows the power is the current
	 * draw (full when not drawing). Worked out from the same physics as {@link #simulate}, without the world.
	 */
	public static float @Nullable [] aimAt(LivingEntity shooter, ItemStack item, Vec3 target) {
		Launch flat = launch(shooter, item, shooter.getYRot(), 0);
		if (flat == null) return null;
		Vec3 from = flat.origin();
		double dx = target.x - from.x, dz = target.z - from.z;
		float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
		double distance = Math.sqrt(dx * dx + dz * dz), rise = target.y - from.y;
		// The shooter's own movement is added to the launch; take it back out so the arc is the item's alone.
		Vec3 own = shooter.getKnownMovement();
		double speed = flat.velocity().subtract(own.x, shooter.onGround() ? 0 : own.y, own.z).length();
		// Height at the target's distance rises with pitch on the low arc until the apex; search the pitch whose arc
		// passes closest, between straight down and 45° up.
		float lo = -90, hi = 45, best = Float.NaN;
		double bestError = Double.MAX_VALUE;
		for (int i = 0; i < 40; i++) {
			float mid = (lo + hi) / 2;
			double y = heightAt(flat.ballistics(), speed, mid, distance);
			if (Double.isNaN(y)) {
				hi = mid;
				continue;
			}
			double error = Math.abs(y - rise);
			if (error < bestError) {
				bestError = error;
				best = mid;
			}
			if (y < rise) hi = mid;
			else lo = mid;
		}
		return Float.isNaN(best) || bestError > 0.5 ? null : new float[]{yaw, -best};
	}

	/** Height reached when the arc has travelled {@code distance} horizontally, or NaN if it never gets that far. */
	private static double heightAt(Ballistics b, double speed, float pitchUp, double distance) {
		double r = Math.toRadians(pitchUp);
		double vx = speed * Math.cos(r), vy = speed * Math.sin(r), x = 0, y = 0;
		for (int tick = 0; tick < 400; tick++) {
			double nx = x + vx, ny = y + vy;
			if (nx >= distance) {
				double t = vx == 0 ? 1 : (distance - x) / vx;
				return y + vy * t;
			}
			x = nx;
			y = ny;
			if (b.movesFirst()) {
				vx *= b.drag();
				vy = vy * b.drag() - b.gravity();
			} else {
				vy -= b.gravity();
				vx *= b.drag();
				vy *= b.drag();
			}
			if (vx <= 1e-4 || y < -512) return Double.NaN;
		}
		return Double.NaN;
	}

	/** {@link #simulate(Level, Launch, int, Entity)} in {@code owner}'s level. */
	public static Path simulate(Launch launch, int maxTicks, Entity owner) {
		return simulate(owner.level(), launch, maxTicks, owner);
	}

	/**
	 * Follows {@code launch} for up to {@code maxTicks} ticks, stopping at the first block or entity in its way.
	 * {@code owner} (may be null) isn't hit, as projectiles don't hit whoever fired them as they leave.
	 */
	public static Path simulate(Level level, Launch launch, int maxTicks, @Nullable Entity owner) {
		Ballistics b = launch.ballistics();
		Vec3 pos = launch.origin(), v = launch.velocity();
		List<Vec3> points = new ArrayList<>();
		points.add(pos);
		Predicate<Entity> hittable = e -> e != owner && e.isAlive() && e.canBeHitByProjectile() && !e.isSpectator();
		for (int tick = 0; tick < maxTicks; tick++) {
			boolean water = level.getFluidState(BlockPos.containing(pos)).is(FluidTags.WATER);
			if (!b.movesFirst()) {
				v = v.subtract(0, b.gravity(), 0).scale(water ? b.waterDrag() : b.drag());
			} else if (water) {
				v = v.scale(b.waterDrag());
			}
			Vec3 next = pos.add(v);
			HitResult hit = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
			if (hit.getType() != HitResult.Type.MISS) next = hit.getLocation();
			EntityHitResult entity = firstEntity(level, pos, next, b.radius(), hittable);
			if (entity != null) {
				points.add(entity.getLocation());
				return new Path(points, entity);
			}
			points.add(next);
			if (hit.getType() != HitResult.Type.MISS) return new Path(points, hit);
			pos = next;
			if (b.movesFirst()) v = (water ? v : v.scale(b.drag())).subtract(0, b.gravity(), 0);
			if (pos.y < level.getMinY() - 64) break;
		}
		return new Path(points, null);
	}

	private static @Nullable EntityHitResult firstEntity(Level level, Vec3 from, Vec3 to, double radius, Predicate<Entity> filter) {
		AABB sweep = new AABB(from, to).inflate(radius + 1);
		EntityHitResult best = null;
		double bestDist = Double.MAX_VALUE;
		for (Entity e : level.getEntities((Entity) null, sweep, filter)) {
			Optional<Vec3> at = e.getBoundingBox().inflate(radius).clip(from, to);
			if (at.isEmpty()) continue;
			double d = from.distanceToSqr(at.get());
			if (d < bestDist) {
				bestDist = d;
				best = new EntityHitResult(e, at.get());
			}
		}
		return best;
	}
}
