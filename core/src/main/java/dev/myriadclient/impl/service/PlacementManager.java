package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.MovementPacketsEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.service.PacketLimits;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.util.BlockInfo;
import dev.myriadclient.api.util.MathUtil;
import dev.myriadclient.api.util.Reach;
import dev.myriadclient.impl.network.BlockAckTracker;
import dev.myriadclient.impl.network.JoinedVersion;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Places blocks and follows each one until the server acknowledges it (see {@link BlockAckTracker}).
 *
 * <p>Rotated placements are queued: each tick the rotation manager is asked to face the first one, and once that
 * rotation has gone out in the movement packet, every queued placement the sent rotation actually looks at is
 * clicked, at the point the look lands on. So the server always sees a placement it could have made by hand, and no
 * extra rotation packets are sent.
 */
public final class PlacementManager implements Placement {
	/**
	 * Placement rotations snap, and walk along the sent yaw while they're held: Grim simulates movement with the yaw it
	 * was sent, so without the fix walking while facing a placement is flagged.
	 */
	static final Rotations.Options MOVE_FIX = Rotations.Options.MOVE_FIX;

	/** Just above the first request's, so the refined aim wins it. */
	static final int REFINED_PRIORITY = Rotations.PRIORITY_HIGH + 1;

	/** How long a rotated placement may wait for its rotation before it's given up. */
	private static final int QUEUE_TICKS = 6;
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
	private record Aim(BlockHitResult hit, double eyeHeight) {
	}

	private record Queued(Object owner, BlockPos pos, Aim aim, int slot, Options options, CompletableFuture<Boolean> result, int expires) {
		BlockHitResult target() {
			return aim.hit;
		}
	}

	private final Minecraft mc = Minecraft.getInstance();
	private final BlockAckTracker acks;
	private final Map<BlockPos, CompletableFuture<Boolean>> pending = new HashMap<>();
	private final List<Queued> queue = new ArrayList<>();
	private int tick;

	public PlacementManager(BlockAckTracker acks) {
		this.acks = acks;
	}

	@Override
	public boolean isPending(BlockPos pos) {
		return pending.containsKey(pos);
	}

	@Override
	public Check check(BlockPos pos, Options o) {
		Check space = spaceCheck(pos, o);
		if (!space.ok()) return space;
		return target(pos, o) != null ? Check.OK : supportCheck(pos, o);
	}

	/** Why no face qualified: nothing to click (and no air face you can see, for air placements), or only faces you can't see. */
	private Check supportCheck(BlockPos pos, Options o) {
		if (!clickTargets(pos).isEmpty()) return Check.NOT_VISIBLE;
		return o.airPlace() ? Check.NOT_VISIBLE : Check.NO_SUPPORT;
	}

	/** Everything but support: replaceable, in range, not pending, no entity in the way, budget left. */
	private Check spaceCheck(BlockPos pos, Options o) {
		if (mc.player == null || mc.level == null) return Check.UNAVAILABLE;
		if (isPending(pos) || Myriad.breaking().isPending(pos)) return Check.PENDING;
		BlockState state = mc.level.getBlockState(pos);
		if (!state.canBeReplaced()) return Check.OCCUPIED;
		if (Double.isNaN(eyeHeightFor(new AABB(pos), o.range()))) return Check.OUT_OF_RANGE;
		boolean blocked = !mc.level.getEntities((net.minecraft.world.entity.Entity) null, new AABB(pos),
			e -> e.isAlive() && e.isPickable() || e == mc.player && e.getBoundingBox().intersects(new AABB(pos))).isEmpty();
		if (blocked) return Check.ENTITY_IN_WAY;
		if (!Myriad.limits().canSend(PacketLimits.Kind.INTERACT, 1)) return Check.RATE_LIMITED;
		return Check.OK;
	}

	/**
	 * The click for {@code pos}: on the best visible face you can reach, any reachable one unless visible faces are
	 * required, or the empty space itself for an air placement.
	 */
	private Aim target(BlockPos pos, Options o) {
		List<BlockHitResult> targets = clickTargets(pos);
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
	private Aim airHit(BlockPos pos, double range, boolean rotate) {
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
	private Aim aimAt(BlockPos pos, Direction face, double range) {
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
	private Aim lookHit(float yaw, float pitch, BlockPos pos, Direction face, double range) {
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
	private double eyeHeightFor(AABB box, double range) {
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
	private Vec3 eyes(double eyeHeight) {
		return new Vec3(mc.player.getX(), mc.player.getY() + eyeHeight, mc.player.getZ());
	}

	/** Whether {@code face} of the block at {@code pos} points towards eyes at {@code eyeHeight} (they're past its plane). */
	private boolean exposed(BlockPos pos, Direction face, double eyeHeight) {
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

	@Override
	public Attempt place(Object owner, BlockPos pos, int hotbarSlot, Options o) {
		if (hotbarSlot < 0 || hotbarSlot > 8) return Attempt.refused(pos, Check.UNAVAILABLE);
		Check space = spaceCheck(pos, o);
		if (!space.ok()) return Attempt.refused(pos, space);
		Aim aim = target(pos, o);
		if (aim == null) return Attempt.refused(pos, supportCheck(pos, o));
		return submit(owner, pos, aim, hotbarSlot, o);
	}

	@Override
	public Attempt place(Object owner, BlockHitResult hit, int hotbarSlot, Options o) {
		BlockPos pos = hit.getBlockPos().relative(hit.getDirection());
		if (hotbarSlot < 0 || hotbarSlot > 8) return Attempt.refused(pos, Check.UNAVAILABLE);
		Check check = spaceCheck(pos, o);
		if (!check.ok()) return Attempt.refused(pos, check);
		double eyeHeight = eyeHeightFor(new AABB(hit.getLocation(), hit.getLocation()), o.range());
		if (Double.isNaN(eyeHeight)) return Attempt.refused(pos, Check.OUT_OF_RANGE);
		Aim aim = new Aim(hit, eyeHeight);
		if (o.visibleFaces() && !visible(aim)) return Attempt.refused(pos, Check.NOT_VISIBLE);
		return submit(owner, pos, aim, hotbarSlot, o);
	}

	@Override
	public void cancel(Object owner) {
		if (owner == null) return;
		for (Queued q : new ArrayList<>(queue)) {
			if (q.owner != owner) continue;
			queue.remove(q);
			q.result.complete(false);
		}
	}

	private Attempt submit(Object owner, BlockPos pos, Aim aim, int slot, Options o) {
		BlockPos key = pos.immutable();
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		pending.put(key, result);
		result.whenComplete((placed, t) -> pending.remove(key, result));
		if (o.rotate()) queue.add(new Queued(owner, key, aim, slot, o, result, tick + QUEUE_TICKS));
		else click(key, aim.hit, slot, o, result);
		return new Attempt(key, Check.OK, result);
	}

	/** Sends the placement now and completes {@code result} with the server's answer. */
	private void click(BlockPos pos, BlockHitResult target, int slot, Options o, CompletableFuture<Boolean> result) {
		int before = acks.currentSequence();
		// Clicking the empty space itself: an air placement.
		boolean air = target.getBlockPos().equals(pos);
		Myriad.inventory().silentSwap(slot, () -> {
			if (air && Myriad.inventory() instanceof InventoryManager inventory) {
				// Swap the block to the off hand, place it from there, swing that hand, and swap back: how 2b2t clients
				// place against air there, past its Grim. Back first thing next tick: Grim (PacketOrderG) cancels an off
				// hand swap after a placement in the same tick, which would leave the block in your off hand. Meanwhile
				// what was in the off hand (a totem) is in the hand the server holds, where it still saves you.
				inventory.swapHands();
				mc.gameMode.useItemOn(mc.player, InteractionHand.OFF_HAND, target);
				swing(InteractionHand.OFF_HAND, o.swing());
				inventory.afterSwap(inventory::swapHands);
			} else {
				mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, target);
				if (o.swing()) mc.player.swing(InteractionHand.MAIN_HAND);
			}
		});
		int sequence = acks.currentSequence();
		// useItemOn gives up before sending anything outside the world border.
		if (sequence == before) {
			result.complete(false);
			return;
		}
		acks.track(sequence, pos).whenComplete((state, t) -> result.complete(t == null && !state.canBeReplaced()));
	}

	/**
	 * The rotation to look at {@code point} from about where your eyes will be when it's sent (the movement packet goes
	 * out after you move this tick). Good enough to settle the yaw; the pitch is refined just before sending.
	 */
	static float[] anglesFromNextPosition(Vec3 point) {
		return anglesFromNextPosition(point, Minecraft.getInstance().player.getEyeHeight());
	}

	/** {@link #anglesFromNextPosition(Vec3)} from eyes {@code eyeHeight} above the feet. */
	static float[] anglesFromNextPosition(Vec3 point, double eyeHeight) {
		Minecraft mc = Minecraft.getInstance();
		return MathUtil.anglesTo(mc.player.position().add(0, eyeHeight, 0).add(mc.player.getDeltaMovement()), point);
	}

	/** Swings {@code hand}: shown if {@code visible}, otherwise only sent (a placement without a swing is noticed). */
	private void swing(InteractionHand hand, boolean visible) {
		if (visible) mc.player.swing(hand);
		else mc.getConnection().send(new ServerboundSwingPacket(hand));
	}

	/** Swaps the main and off hand, on the client and the server, as the swap key does. */
	/** Asks to face the first queued placement; it's clicked once that rotation has been sent. */
	@Subscribe
	private void onTickStart(TickEvent.Pre e) {
		if (queue.isEmpty() || mc.player == null) return;
		Aim aim = queue.getFirst().aim;
		float[] r = anglesFromNextPosition(aim.hit.getLocation(), aim.eyeHeight);
		Myriad.rotations().request(this, r[0], r[1], Rotations.PRIORITY_HIGH, MOVE_FIX, null);
	}

	/**
	 * Aims again just before the rotation goes out, now that you've moved this tick: the yaw is already fixed (walking
	 * follows it), but the pitch can still be exact. Looking down at the side of the block you stand on, a tenth of a
	 * block of error in where your eyes are swings the look by most of a block.
	 */
	@Subscribe(priority = Priority.HIGH)
	private void onBeforeRotationSent(MovementPacketsEvent e) {
		if (queue.isEmpty() || mc.player == null) return;
		Aim aim = queue.getFirst().aim;
		float[] r = MathUtil.anglesTo(eyes(aim.eyeHeight), aim.hit.getLocation());
		Myriad.rotations().request(this, r[0], r[1], REFINED_PRIORITY, MOVE_FIX, null);
	}

	/**
	 * Clicks every queued placement the rotation sent last tick looks at, where the look lands. At the start of the tick,
	 * before this tick's movement: the server already faces that way and your eyes haven't moved since, and it's where
	 * vanilla clicks (Grim flags clicks sent after a movement packet).
	 */
	@Subscribe(priority = RotationManager.ACT_PRIORITY - 10)
	private void onClickTime(TickEvent.Pre e) {
		if (queue.isEmpty() || mc.player == null) return;
		float yaw = Myriad.rotations().serverYaw(), pitch = Myriad.rotations().serverPitch();
		Iterator<Queued> it = queue.iterator();
		while (it.hasNext()) {
			Queued q = it.next();
			if (q.result.isDone()) {
				it.remove();
				continue;
			}
			Aim look = lookHit(yaw, pitch, q.target().getBlockPos(), q.target().getDirection(), q.options.range());
			if (look == null) continue;
			it.remove();
			// The world may have changed while it waited (an air placement clicks the space itself).
			boolean air = q.target().getBlockPos().equals(q.pos);
			if (!mc.level.getBlockState(q.pos).canBeReplaced() || !air && mc.level.getBlockState(q.target().getBlockPos()).canBeReplaced()
				|| !Myriad.limits().canSend(PacketLimits.Kind.INTERACT, 1)) {
				q.result.complete(false);
				continue;
			}
			click(q.pos, look.hit, q.slot, q.options, q.result);
		}
	}

	@Subscribe
	private void onTickEnd(TickEvent.Post e) {
		tick++;
		if (queue.isEmpty()) return;
		queue.removeIf(q -> {
			if (q.result.isDone()) return true;
			if (tick < q.expires && mc.player != null) return false;
			q.result.complete(false);
			return true;
		});
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		for (Queued q : new ArrayList<>(queue)) q.result.complete(false);
		queue.clear();
		pending.clear();
	}

	@Override
	public List<BlockHitResult> clickTargets(BlockPos pos) {
		List<BlockHitResult> out = new ArrayList<>();
		if (mc.player == null || mc.level == null) return out;
		Vec3 eyes = mc.player.getEyePosition();
		for (Direction dir : Direction.values()) {
			BlockPos neighbour = pos.relative(dir);
			BlockState state = mc.level.getBlockState(neighbour);
			if (state.canBeReplaced() || (BlockInfo.isClickable(state) && !mc.player.isShiftKeyDown())) continue;
			Direction face = dir.getOpposite();
			Vec3 hitVec = Vec3.atCenterOf(neighbour).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
			out.add(new BlockHitResult(hitVec, face, neighbour, false));
		}
		out.sort(Comparator.<BlockHitResult, Boolean>comparing(h -> !visible(h)).thenComparingDouble(h -> eyes.distanceToSqr(h.getLocation())));
		return out;
	}

	/** The clicked face points towards the eyes and nothing stands between them and the click point. */
	private boolean visible(BlockHitResult hit) {
		return visible(new Aim(hit, mc.player.getEyeHeight()));
	}

	/** {@link #visible(BlockHitResult)} from the eyes the click is aimed from. */
	private boolean visible(Aim aim) {
		BlockHitResult hit = aim.hit;
		if (!exposed(hit.getBlockPos(), hit.getDirection(), aim.eyeHeight)) return false;
		BlockHitResult ray = mc.level.clip(new ClipContext(eyes(aim.eyeHeight), hit.getLocation(), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
		return ray.getType() == HitResult.Type.MISS || ray.getBlockPos().equals(hit.getBlockPos());
	}
}
