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
import org.jetbrains.annotations.Nullable;

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
	 * on the nearest face, preferring faces you can see. Each face is tried at its point nearest the eyes (so the whole
	 * reach counts), then at its centre in case something hides the edge. With {@code mustSee}, only visible faces
	 * count (strict anti-cheats check this). Null if no face qualifies.
	 */
	public static @Nullable BlockHitResult hitFor(BlockPos pos, boolean mustSee, double range) {
		if (mc().level == null || mc().player == null) return null;
		Vec3 eyes = eyes();
		double r2 = range * range;
		BlockHitResult best = null, bestHidden = null;
		double bestDist = Double.MAX_VALUE, bestHiddenDist = Double.MAX_VALUE;
		for (Direction face : Direction.values()) {
			boolean exposed = faceExposed(pos, face);
			for (Vec3 point : new Vec3[]{nearestOnFace(pos, face, eyes), Positions.faceCenter(pos, face)}) {
				double d = eyes.distanceToSqr(point);
				if (d > r2) continue;
				// Seeing a face means the ray from the eyes lands on this block (not one in front of it).
				BlockHitResult ray = mc().level.clip(new ClipContext(eyes, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc().player));
				boolean visible = exposed && (ray.getType() == HitResult.Type.MISS || ray.getBlockPos().equals(pos));
				BlockHitResult hit = new BlockHitResult(point, face, pos, false);
				if (visible) {
					if (d < bestDist) {
						best = hit;
						bestDist = d;
					}
					break;
				}
				if (d < bestHiddenDist) {
					bestHidden = hit;
					bestHiddenDist = d;
				}
			}
		}
		return best != null || mustSee ? best : bestHidden;
	}

	/** How far in from the edges {@link #nearestOnFace} stays, so the ray lands on the face rather than its rim. */
	private static final double EDGE_INSET = 0.01;

	/** The point of {@code face} of the block at {@code pos} nearest to {@code from}. */
	private static Vec3 nearestOnFace(BlockPos pos, Direction face, Vec3 from) {
		double x = Math.clamp(from.x, pos.getX() + EDGE_INSET, pos.getX() + 1 - EDGE_INSET);
		double y = Math.clamp(from.y, pos.getY() + EDGE_INSET, pos.getY() + 1 - EDGE_INSET);
		double z = Math.clamp(from.z, pos.getZ() + EDGE_INSET, pos.getZ() + 1 - EDGE_INSET);
		double plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
		return switch (face.getAxis()) {
			case X -> new Vec3(pos.getX() + plane, y, z);
			case Y -> new Vec3(x, pos.getY() + plane, z);
			case Z -> new Vec3(x, y, pos.getZ() + plane);
		};
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
