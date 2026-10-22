package dev.myriadclient.api.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What the player can reach and see, measured from the eyes as the server does. Ranges are the player's attribute
 * values (4.5 for blocks and 3 for entities in survival), so they follow creative mode and attribute changes.
 */
public final class Reach {
	private Reach() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	public static double blockRange() {
		return mc().player == null ? 0 : mc().player.blockInteractionRange();
	}

	public static double entityRange() {
		return mc().player == null ? 0 : mc().player.entityInteractionRange();
	}

	public static Vec3 eyes() {
		return mc().player == null ? Vec3.ZERO : mc().player.getEyePosition();
	}

	/** Whether nothing solid lies between two points. */
	public static boolean canSee(Vec3 from, Vec3 to) {
		if (mc().level == null || mc().player == null) return false;
		return mc().level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc().player))
			.getType() == HitResult.Type.MISS;
	}

	/** Whether nothing solid lies between the eyes and {@code point}. */
	public static boolean canSee(Vec3 point) {
		return canSee(eyes(), point);
	}

	/** Whether {@code point} is within block reach of the eyes. */
	public static boolean canReach(Vec3 point) {
		return mc().player != null && eyes().distanceToSqr(point) <= blockRange() * blockRange();
	}

	/** Whether the server would accept hitting {@code entity} (its box within entity reach). */
	public static boolean canReach(Entity entity) {
		return mc().player != null && MathUtil.distanceTo(entity.getBoundingBox(), eyes()) <= entityRange();
	}

	/**
	 * Whether the server would accept interacting with the block at {@code pos}: the nearest point of its block space
	 * is within block reach of the eyes. That's how the server and Grim (2b2t) measure, so it's the whole reach a
	 * click on the block's near side gets, not a distance to its centre.
	 */
	public static boolean canReach(BlockPos pos) {
		return canReach(pos, blockRange());
	}

	/** {@link #canReach(BlockPos)} with a reach of {@code range}. */
	public static boolean canReach(BlockPos pos, double range) {
		return mc().player != null && new AABB(pos).distanceToSqr(eyes()) <= range * range;
	}

	/** The point on {@code entity}'s box closest to the eyes, where a hit or rotation should aim. */
	public static Vec3 aimPoint(Entity entity) {
		return MathUtil.closestPoint(entity.getBoundingBox(), eyes());
	}

	/** {@link #hitFor(BlockPos, boolean, double)} within block reach. */
	public static @Nullable BlockHitResult hitFor(BlockPos pos, boolean mustSee) {
		return hitFor(pos, mustSee, blockRange());
	}

	/**
	 * A click on the block at {@code pos} for opening, breaking or using it: the nearest point within {@code range}
	 * on the nearest face of its real outline (a slab's top is half way up), preferring faces you can see. Each face is
	 * tried at its point nearest the eyes (so the whole reach counts), then at its centre in case something hides the
	 * edge. With {@code mustSee}, only visible faces count (strict anti-cheats check this). Null if no face qualifies.
	 */
	public static @Nullable BlockHitResult hitFor(BlockPos pos, boolean mustSee, double range) {
		if (mc().level == null || mc().player == null) return null;
		Vec3 eyes = eyes();
		double r2 = range * range;
		BlockHitResult best = null, bestHidden = null;
		double bestDist = Double.MAX_VALUE, bestHiddenDist = Double.MAX_VALUE;
		for (Direction face : Direction.values()) {
			for (Vec3 point : clickPoints(pos, face, false)) {
				double d = eyes.distanceToSqr(point);
				if (d > r2) continue;
				BlockHitResult hit = new BlockHitResult(point, face, pos, false);
				if (canSee(hit)) {
					if (d < bestDist) {
						best = hit;
						bestDist = d;
					}
				} else if (d < bestHiddenDist) {
					bestHidden = hit;
					bestHiddenDist = d;
				}
			}
		}
		return best != null || mustSee ? best : bestHidden;
	}

	/** How far a point is moved off the surface to check it's on the outside of the outline there. */
	private static final double PROBE = 0.01;
	/** Where across a face part the spread points sit, as shares of its width from one edge. */
	private static final double[] SPREAD = {0.2, 0.5, 0.8};

	/**
	 * Points where the block at {@code pos} can really be clicked on its side facing {@code face}: on its outline (a
	 * slab's top is half way up, a candle is a sliver in the middle, a stair has two tops), not the cube around it, and
	 * only where no other part of the block covers it. For each part of the outline with a side that way, its centre
	 * and its point nearest your eyes (a little in from the edges), and with {@code spread} a 3x3 grid over it too,
	 * for blocks where where you click matters (a slab's half, a door's hinge). For an empty space (air), the cube's.
	 */
	public static List<Vec3> clickPoints(BlockPos pos, Direction face, boolean spread) {
		List<Vec3> out = new ArrayList<>();
		if (mc().level == null) return out;
		VoxelShape shape = mc().level.getBlockState(pos).getShape(mc().level, pos);
		if (shape.isEmpty()) shape = Shapes.block();
		Vec3 eyes = eyes();
		Direction.Axis axis = face.getAxis();
		Direction.Axis u = axis == Direction.Axis.X ? Direction.Axis.Y : Direction.Axis.X, v = axis == Direction.Axis.Z ? Direction.Axis.Y : Direction.Axis.Z;
		for (AABB part : shape.toAabbs()) {
			AABB b = part.move(pos);
			double at = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? b.max(axis) : b.min(axis);
			double u0 = b.min(u), u1 = b.max(u), v0 = b.min(v), v1 = b.max(v);
			double inset = Math.min(0.05, Math.min(u1 - u0, v1 - v0) / 4);
			addOutside(out, shape, pos, face, point(axis, at, u, (u0 + u1) / 2, v, (v0 + v1) / 2));
			addOutside(out, shape, pos, face, point(axis, at, u, Math.clamp(eyes.get(u), u0 + inset, u1 - inset), v, Math.clamp(eyes.get(v), v0 + inset, v1 - inset)));
			if (!spread) continue;
			for (double fu : SPREAD) {
				for (double fv : SPREAD) {
					if (fu != 0.5 || fv != 0.5) addOutside(out, shape, pos, face, point(axis, at, u, u0 + (u1 - u0) * fu, v, v0 + (v1 - v0) * fv));
				}
			}
		}
		return out;
	}

	/**
	 * Whether a look from your eyes at {@code hit}'s point would land on that face of that block: the face points
	 * towards you (you're past the plane it's in, a slab's top half way up) and nothing comes first, neither another
	 * block nor another face of this one (from above, a look at the top of a side face enters through the top).
	 */
	public static boolean canSee(BlockHitResult hit) {
		if (mc().level == null || mc().player == null) return false;
		Vec3 e = eyes(), p = hit.getLocation();
		boolean exposed = switch (hit.getDirection()) {
			case UP -> e.y > p.y;
			case DOWN -> e.y < p.y;
			case EAST -> e.x > p.x;
			case WEST -> e.x < p.x;
			case SOUTH -> e.z > p.z;
			case NORTH -> e.z < p.z;
		};
		if (!exposed) return false;
		BlockHitResult ray = mc().level.clip(new ClipContext(e, p, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc().player));
		return ray.getType() == HitResult.Type.MISS || ray.getBlockPos().equals(hit.getBlockPos()) && ray.getDirection() == hit.getDirection();
	}

	/** Adds {@code p} if it's on the outside of {@code shape}, facing {@code face}. */
	private static void addOutside(List<Vec3> out, VoxelShape shape, BlockPos pos, Direction face, Vec3 p) {
		Vec3 n = Vec3.atLowerCornerOf(face.getUnitVec3i());
		BlockHitResult hit = shape.clip(p.add(n.scale(PROBE)), p.subtract(n.scale(PROBE)), pos);
		if (hit != null && hit.getDirection() == face && hit.getLocation().distanceToSqr(p) < PROBE * PROBE * 4) out.add(p);
	}

	private static Vec3 point(Direction.Axis axis, double at, Direction.Axis u, double uValue, Direction.Axis v, double vValue) {
		double[] xyz = new double[3];
		xyz[axis.ordinal()] = at;
		xyz[u.ordinal()] = uValue;
		xyz[v.ordinal()] = vValue;
		return new Vec3(xyz[0], xyz[1], xyz[2]);
	}

	/**
	 * Whether {@code face} of the block at {@code pos} points towards the eyes (the eyes are past its plane). A face
	 * pointing away can't be seen however the player turns; servers that check clicks reject them.
	 */
	public static boolean faceExposed(BlockPos pos, Direction face) {
		Vec3 e = eyes();
		return switch (face) {
			case UP -> e.y > pos.getY() + 1;
			case DOWN -> e.y < pos.getY();
			case EAST -> e.x > pos.getX() + 1;
			case WEST -> e.x < pos.getX();
			case SOUTH -> e.z > pos.getZ() + 1;
			case NORTH -> e.z < pos.getZ();
		};
	}

	/**
	 * Where looking along {@code yaw}/{@code pitch} from the eyes first meets the full block space at {@code pos},
	 * within {@code range}: the point and face a server would see you aiming at. Null if the look misses it.
	 * Pass the server rotation ({@code Myriad.rotations().serverYaw()}) to check what an action will look like.
	 */
	public static @Nullable BlockHitResult rayHit(float yaw, float pitch, BlockPos pos, double range) {
		if (mc().player == null) return null;
		Vec3 from = eyes();
		Vec3 to = from.add(MathUtil.direction(yaw, pitch).scale(range));
		return AABB.clip(FULL_BLOCK, from, to, pos);
	}

	private static final List<AABB> FULL_BLOCK = List.of(new AABB(0, 0, 0, 1, 1, 1));
}
