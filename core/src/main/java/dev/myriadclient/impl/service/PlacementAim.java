package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.Placement.Options;
import dev.myriadclient.api.util.BlockInfo;
import dev.myriadclient.api.util.MathUtil;
import dev.myriadclient.api.util.Reach;
import dev.myriadclient.impl.network.JoinedVersion;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Where {@link PlacementManager} clicks, and whether the server takes it from there: the point on a face to aim at, from
 * your own eyes or any height Grim allows, reach as the server measures it, which faces you can see, and where a look
 * lands. Answers questions; sends nothing.
 */
final class PlacementAim {
	/**
	 * How far in from the edges of a face a click aims, at the point nearest the eyes: off the rim, so a little error
	 * in where the eyes are doesn't land the look on the next face, and at a different spot each time you've moved.
	 */
	private static final double INSET = 0.3;
	/**
	 * How far each click strays from that point, at random, within the face. Grim (DuplicateRotPlace, experimental)
	 * flags placements turned into by exactly the same yaw as the last one, which a straight walk gives every block.
	 */
	private static final double SPREAD = 0.15;
	/** The same near the limit of reach, where the last few tenths count. */
	private static final double EDGE_INSET = 0.02;
	/** Reach kept back when choosing where to aim, so the click's ray still gets there after rounding. */
	private static final double REACH_SLACK = 0.01;

	/**
	 * Heights above the feet Grim takes the eyes to be at for a click, whatever pose it thinks you're in (it can't
	 * tell): standing, sneaking, and swimming or gliding. FarPlace, RotationPlace and PositionPlace each accept a
	 * click that works from any of them, so a block below you reaches as far from the lowest as the attribute allows.
	 */
	private static final double[] EYE_HEIGHTS = {1.62, 1.27, 0.4};
	/** Before 1.14 sneaking lowered the eyes less. */
	private static final double[] EYE_HEIGHTS_BEFORE_1_14 = {1.62, 1.54, 0.4};
	/** Before 1.9 there was no swimming or gliding pose. */
	private static final double[] EYE_HEIGHTS_BEFORE_1_9 = {1.62, 1.54};

	/** A click and the height above the feet it's aimed from. */
	record Aim(BlockHitResult hit, double eyeHeight) {
	}

	private final Minecraft mc = Minecraft.getInstance();

	Aim target(BlockPos pos, Options o) {
		List<BlockHitResult> targets = clickTargets(pos, o.sneak());
		if (targets.isEmpty()) return o.airPlace() ? airHit(pos, o.range(), o.rotate()) : null;
		for (BlockHitResult t : targets) {
			Aim aim = aimAt(t.getBlockPos(), t.getDirection(), o.range());
			if (aim == null) continue;
			if (o.visibleFaces() && !visible(aim)) return null;
			return aim;
		}
		return null;
	}

	/**
	 * An air placement's click: where the look the server has enters the empty space, if it does (as a vanilla click
	 * would be); otherwise, if it will {@code rotate} to it, on the face nearest your eyes that faces them, where you'd
	 * be looking to aim at it. A face turned away from you, a point out of reach or (unrotated) a click where you aren't
	 * looking is refused by servers that check clicks, so null if none qualifies.
	 */
	Aim airHit(BlockPos pos, double range, boolean rotate) {
		Aim look = lookHit(Myriad.rotations().serverYaw(), Myriad.rotations().serverPitch(), pos, null, range);
		if (look != null || !rotate) return look;
		Aim best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Direction face : Direction.values()) {
			Aim aim = aimAt(pos, face, range);
			if (aim == null) continue;
			double d = eyes(aim.eyeHeight).distanceToSqr(aim.hit.getLocation());
			if (d >= bestDistance) continue;
			bestDistance = d;
			best = aim;
		}
		return best;
	}

	/**
	 * Where to click {@code face} of the block at {@code pos}, and from which eye height: near the point nearest the
	 * eyes, from your own eyes if they reach it, otherwise from whichever height Grim allows that does. Null if the face
	 * points away from all of them or none reaches.
	 */
	Aim aimAt(BlockPos pos, Direction face, double range) {
		double reach = range - REACH_SLACK;
		for (double inset : new double[]{INSET, EDGE_INSET}) {
			double own = mc.player.getEyeHeight();
			Vec3 point = spread(pointOnFace(pos, face, eyes(own), inset), pos, face, inset);
			if (exposed(pos, face, own) && eyes(own).distanceToSqr(point) <= reach * reach) return aim(point, face, pos, own);
			Aim best = null;
			double bestDistance = reach * reach;
			for (double h : eyeHeights()) {
				Vec3 p = spread(pointOnFace(pos, face, eyes(h), inset), pos, face, inset);
				double d = eyes(h).distanceToSqr(p);
				if (!exposed(pos, face, h) || d > bestDistance || !serverReaches(p, range)) continue;
				best = aim(p, face, pos, h);
				bestDistance = d;
			}
			if (best != null) return best;
		}
		return null;
	}

	private static Aim aim(Vec3 point, Direction face, BlockPos pos, double eyeHeight) {
		return new Aim(new BlockHitResult(point, face, pos, false), eyeHeight);
	}

	/**
	 * Where a look of {@code yaw}/{@code pitch} lands on the block at {@code pos} within {@code range}, from your own
	 * eyes or any height Grim allows, as Grim's RotationPlace traces it; on {@code face} if given, and only on a face
	 * pointing towards those eyes. Null if it lands nowhere that counts.
	 */
	Aim lookHit(float yaw, float pitch, BlockPos pos, Direction face, double range) {
		Vec3 direction = MathUtil.direction(yaw, pitch).scale(range);
		Aim own = lookHitFrom(mc.player.getEyeHeight(), direction, pos, face);
		if (own != null) return own;
		for (double h : eyeHeights()) {
			Aim aim = lookHitFrom(h, direction, pos, face);
			if (aim != null && serverReaches(aim.hit.getLocation(), range)) return aim;
		}
		return null;
	}

	private Aim lookHitFrom(double eyeHeight, Vec3 direction, BlockPos pos, Direction face) {
		Vec3 from = eyes(eyeHeight);
		BlockHitResult hit = AABB.clip(FULL_BLOCK, from, from.add(direction), pos);
		if (hit == null || face != null && hit.getDirection() != face || !exposed(pos, hit.getDirection(), eyeHeight)) return null;
		return new Aim(new BlockHitResult(hit.getLocation(), hit.getDirection(), pos, false), eyeHeight);
	}

	private static final List<AABB> FULL_BLOCK = List.of(new AABB(0, 0, 0, 1, 1, 1));

	/**
	 * The eye height to reach {@code box} from: your own if they reach it, otherwise the nearest Grim allows. NaN if
	 * none does.
	 */
	double eyeHeightFor(AABB box, double range) {
		double own = mc.player.getEyeHeight();
		if (box.distanceToSqr(eyes(own)) <= range * range) return own;
		double best = Double.NaN, bestDistance = range * range;
		for (double h : eyeHeights()) {
			double d = box.distanceToSqr(eyes(h));
			if (d > bestDistance || !serverReaches(box, range)) continue;
			best = h;
			bestDistance = d;
		}
		return best;
	}

	/** The server's own limit, from your actual eyes: the reach plus one block. */
	private boolean serverReaches(AABB box, double range) {
		double limit = Math.min(range, Reach.blockRange()) + 1;
		return box.distanceToSqr(mc.player.getEyePosition()) <= limit * limit;
	}

	private boolean serverReaches(Vec3 point, double range) {
		return serverReaches(new AABB(point, point), range);
	}

	/** {@link #EYE_HEIGHTS} for the version you joined as, scaled with you. */
	private double[] eyeHeights() {
		int protocol = JoinedVersion.get().protocol();
		double[] heights = protocol < 0 || protocol >= 477 ? EYE_HEIGHTS : protocol >= 107 ? EYE_HEIGHTS_BEFORE_1_14 : EYE_HEIGHTS_BEFORE_1_9;
		float scale = mc.player.getScale();
		if (scale == 1f) return heights;
		double[] scaled = new double[heights.length];
		for (int i = 0; i < heights.length; i++) scaled[i] = heights[i] * scale;
		return scaled;
	}

	/** Your eyes at {@code eyeHeight} above your feet. */
	Vec3 eyes(double eyeHeight) {
		return new Vec3(mc.player.getX(), mc.player.getY() + eyeHeight, mc.player.getZ());
	}

	/** Whether {@code face} of the block at {@code pos} points towards eyes at {@code eyeHeight} (they're past its plane). */
	boolean exposed(BlockPos pos, Direction face, double eyeHeight) {
		Vec3 e = eyes(eyeHeight);
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
	 * {@code point} moved up to {@link #SPREAD} along {@code face}, at random, staying half the inset in from the edges
	 * (not at all near the edge of reach, where every bit counts).
	 */
	private static Vec3 spread(Vec3 point, BlockPos pos, Direction face, double inset) {
		if (inset < INSET) return point;
		double min = inset / 2;
		ThreadLocalRandom r = ThreadLocalRandom.current();
		double x = face.getAxis() == Direction.Axis.X ? point.x : Math.clamp(point.x + r.nextDouble(-SPREAD, SPREAD), pos.getX() + min, pos.getX() + 1 - min);
		double y = face.getAxis() == Direction.Axis.Y ? point.y : Math.clamp(point.y + r.nextDouble(-SPREAD, SPREAD), pos.getY() + min, pos.getY() + 1 - min);
		double z = face.getAxis() == Direction.Axis.Z ? point.z : Math.clamp(point.z + r.nextDouble(-SPREAD, SPREAD), pos.getZ() + min, pos.getZ() + 1 - min);
		return new Vec3(x, y, z);
	}

	/** The point of {@code face} nearest {@code from}, at least {@code inset} in from its edges. */
	private static Vec3 pointOnFace(BlockPos pos, Direction face, Vec3 from, double inset) {
		double x = Math.clamp(from.x, pos.getX() + inset, pos.getX() + 1 - inset);
		double y = Math.clamp(from.y, pos.getY() + inset, pos.getY() + 1 - inset);
		double z = Math.clamp(from.z, pos.getZ() + inset, pos.getZ() + 1 - inset);
		double plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
		return switch (face.getAxis()) {
			case X -> new Vec3(pos.getX() + plane, y, z);
			case Y -> new Vec3(x, pos.getY() + plane, z);
			case Z -> new Vec3(x, y, pos.getZ() + plane);
		};
	}

	/** Where a look of {@code yaw}/{@code pitch} from the eyes meets the outline of the block at {@code pos}, within {@code range}. */
	@Nullable BlockHitResult outlineHit(BlockPos pos, float yaw, float pitch, double range) {
		Vec3 from = mc.player.getEyePosition();
		VoxelShape shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
		if (shape.isEmpty()) return null;
		return shape.clip(from, from.add(MathUtil.direction(yaw, pitch).scale(range)), pos);
	}

	/** {@code clickTargets(pos)}, also against blocks that act on a click when the placement {@code sneaks}. */
	List<BlockHitResult> clickTargets(BlockPos pos, boolean sneaks) {
		List<BlockHitResult> out = new ArrayList<>();
		if (mc.player == null || mc.level == null) return out;
		Vec3 eyes = mc.player.getEyePosition();
		for (Direction dir : Direction.values()) {
			BlockPos neighbour = pos.relative(dir);
			BlockState state = mc.level.getBlockState(neighbour);
			if (state.canBeReplaced() || (BlockInfo.isClickable(state) && !sneaks && !mc.player.isShiftKeyDown())) continue;
			Direction face = dir.getOpposite();
			// The middle of the neighbour's real side (a slab's top is half way up), the cube's if it has none there.
			List<Vec3> points = Reach.clickPoints(neighbour, face, false);
			Vec3 hitVec = points.isEmpty() ? Vec3.atCenterOf(neighbour).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5)) : points.getFirst();
			out.add(new BlockHitResult(hitVec, face, neighbour, false));
		}
		out.sort(Comparator.<BlockHitResult, Boolean>comparing(h -> !visible(h)).thenComparingDouble(h -> eyes.distanceToSqr(h.getLocation())));
		return out;
	}

	/**
	 * The clicked face points towards the eyes and the ray to the click point reaches it through that face: not through
	 * a block in front, nor through another face of the block itself (from above, a ray to the top of a side face enters
	 * through the top, and the click would land there).
	 */
	boolean visible(BlockHitResult hit) {
		return visible(new Aim(hit, mc.player.getEyeHeight()));
	}

	/** {@link #visible(BlockHitResult)} from the eyes the click is aimed from. */
	boolean visible(Aim aim) {
		BlockHitResult hit = aim.hit;
		if (!exposed(hit.getBlockPos(), hit.getDirection(), aim.eyeHeight)) return false;
		BlockHitResult ray = mc.level.clip(new ClipContext(eyes(aim.eyeHeight), hit.getLocation(), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
		return ray.getType() == HitResult.Type.MISS || ray.getBlockPos().equals(hit.getBlockPos()) && ray.getDirection() == hit.getDirection();
	}
}
