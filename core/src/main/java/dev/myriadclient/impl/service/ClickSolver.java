package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.build.BlockRules;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.service.Placement.Check;
import dev.myriadclient.api.service.Placement.Click;
import dev.myriadclient.api.service.Placement.Solution;
import dev.myriadclient.api.util.BlockInfo;
import dev.myriadclient.api.util.MathUtil;
import dev.myriadclient.api.util.Reach;
import dev.myriadclient.impl.mixin.BlockItemAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Finds a click that makes a block come out exactly as wanted ({@link Placement#solve}). Every face that could be
 * clicked, at a few points on it, is tried the way the game would place the item there (the face, where on it, and
 * which way you look all count):
 * <ul>
 *   <li>Where the server checks that you look at what you click, the look goes to the point clicked, and a click is
 *   only kept if it still comes out right with the look a little off, so the difference between the rotation sent and
 *   where the server takes your eyes to be can't flip it. Failing that, a clean rotation that gives it, with the look
 *   going to the point in the packet after (Grim's RotationPlace takes that one or the one before).</li>
 *   <li>Otherwise the look you already have is tried first, then a few clean rotations (each way you can face, level,
 *   straight up and straight down; sixteen headings for signs and skulls), clicking anywhere.</li>
 * </ul>
 * The same simulation checks a turned click once more against the look the server really has, just before it's made
 * ({@link #places}).
 */
final class ClickSolver {
	/** Reach kept back when choosing points, so the look at one still reaches it. */
	private static final double REACH_SLACK = 0.05;
	/** How far clean rotations are turned off their heading at random, in degrees (see {@link #rotations}). */
	private static final double JITTER = 3;
	/** How far off the look may be, in degrees each way, and a click aimed at its point still come out right. */
	private static final float TOLERANCE = 1.5f;

	private record Candidate(BlockHitResult hit, boolean visible, double distance, boolean sneak) {
	}

	/** A face that could be clicked: of the block at {@code clicked}, facing {@code face}, sneaking or not. */
	private record Face(BlockPos clicked, Direction face, boolean sneak) {
	}

	private final Minecraft mc = Minecraft.getInstance();
	private final PlacementManager placement;

	ClickSolver(PlacementManager placement) {
		this.placement = placement;
	}

	Solution solve(BlockPos pos, BlockState wanted, ItemStack stack, Placement.Options o) {
		if (mc.player == null || mc.level == null) return Solution.none(Check.UNAVAILABLE);
		if (!(stack.getItem() instanceof BlockItem)) return Solution.none(Check.UNAVAILABLE);
		BlockState current = mc.level.getBlockState(pos);
		if (current.is(wanted.getBlock()) && BlockRules.count(current) < BlockRules.count(wanted) && BlockRules.placedMatches(current, wanted)) {
			return grow(pos, current, wanted, stack, o);
		}
		if (!current.canBeReplaced()) return Solution.none(Check.OCCUPIED);
		return place(pos, wanted, stack, o);
	}

	/** A click putting {@code wanted} at the empty (replaceable) {@code pos}: against a neighbour, or into the space itself with air placement. */
	private Solution place(BlockPos pos, BlockState wanted, ItemStack stack, Placement.Options o) {
		List<Face> faces = new ArrayList<>(18);
		boolean anySupport = false, sneaking = mc.player.isSecondaryUseActive();
		for (Direction d : Direction.values()) {
			BlockPos n = pos.relative(d);
			BlockState s = mc.level.getBlockState(n);
			if (s.canBeReplaced()) continue;
			anySupport = true;
			// Clicking it would open or toggle it instead, unless sneaking.
			if (!BlockInfo.isClickable(s) || sneaking) faces.add(new Face(n, d.getOpposite(), false));
		}
		if (o.airPlace() && mc.level.getBlockState(pos).isAir()) for (Direction d : Direction.values()) faces.add(new Face(pos, d, false));
		// Then sneaking: against blocks that would open or toggle, and everywhere for blocks that come out otherwise
		// beside a neighbour (a chest that would join the one next to it).
		if (!sneaking) {
			for (Direction d : Direction.values()) {
				BlockPos n = pos.relative(d);
				if (!mc.level.getBlockState(n).canBeReplaced()) faces.add(new Face(n, d.getOpposite(), true));
			}
		}
		if (faces.isEmpty()) return Solution.none(anySupport ? Check.NOT_VISIBLE : Check.NO_SUPPORT);
		int count = BlockRules.count(wanted);
		return solve(pos, wanted, faces, stack, r -> r.is(wanted.getBlock()) && BlockRules.placedMatches(r, wanted) && BlockRules.count(r) <= count, count, o);
	}

	/** A click adding one more of the block at {@code pos} (a candle, a pickle, a layer, a slab's other half), keeping it placed as {@code wanted} has it. */
	private Solution grow(BlockPos pos, BlockState current, BlockState wanted, ItemStack stack, Placement.Options o) {
		List<Face> faces = new ArrayList<>(6);
		for (Direction d : Direction.values()) faces.add(new Face(pos, d, false));
		int want = BlockRules.count(current) + 1;
		return solve(pos, wanted, faces, stack, r -> r.is(wanted.getBlock()) && BlockRules.count(r) == want && BlockRules.placedMatches(r, wanted), want, o);
	}

	/**
	 * Whether making {@code click} at {@code hit} with {@code stack}, while the server has the look {@code yaw}/{@code pitch},
	 * still gives what it was solved for: the last check before a turned click goes out.
	 */
	boolean places(Click click, BlockHitResult hit, ItemStack stack, float yaw, float pitch) {
		if (!(stack.getItem() instanceof BlockItem item)) return false;
		BlockState wanted = click.wanted();
		int count = click.count();
		boolean grows = mc.level.getBlockState(click.pos()).is(wanted.getBlock());
		Expect expect = r -> r.is(wanted.getBlock()) && BlockRules.placedMatches(r, wanted) && (grows ? BlockRules.count(r) == count : BlockRules.count(r) <= count);
		float y = mc.player.getYRot(), p = mc.player.getXRot(), yO = mc.player.yRotO, pO = mc.player.xRotO;
		try {
			BlockState r = simulate(item, stack, hit, click.pos(), yaw, pitch, click.sneak());
			return r != null && expect.test(r);
		} finally {
			restore(y, p, yO, pO);
		}
	}

	/** Which results count. */
	private interface Expect {
		boolean test(BlockState result);
	}

	private Solution solve(BlockPos pos, BlockState wanted, List<Face> faces, ItemStack stack, Expect expect, int count, Placement.Options o) {
		BlockItem item = (BlockItem) stack.getItem();
		Vec3 eyes = mc.player.getEyePosition();
		// Kept back from the edge of reach, so a look of exactly the range still gets there after rounding.
		double range = o.range() - REACH_SLACK;
		boolean strict = o.rotate(), mustSee = o.visibleFaces();

		// Where on the face only matters for halves and hinges, and for reach and sight when the look must be on it.
		boolean spread = strict || positional(wanted);
		List<Candidate> candidates = new ArrayList<>();
		boolean anyInRange = false;
		for (Face face : faces) {
			// On the block's real outline (a slab's top is half way up), not the cube around it.
			for (Vec3 point : Reach.clickPoints(face.clicked, face.face, spread)) {
				double d = eyes.distanceToSqr(point);
				if (d > range * range) continue;
				anyInRange = true;
				BlockHitResult hit = new BlockHitResult(point, face.face, face.clicked, false);
				boolean visible = Reach.canSee(hit);
				if (mustSee && !visible) continue;
				candidates.add(new Candidate(hit, visible, d, face.sneak));
			}
		}
		if (candidates.isEmpty()) return Solution.none(anyInRange ? Check.NOT_VISIBLE : Check.OUT_OF_RANGE);
		// Without sneaking first, then faces you can see, then nearest.
		candidates.sort(Comparator.<Candidate, Boolean>comparing(c -> c.sneak).thenComparing(c -> !c.visible).thenComparingDouble(Candidate::distance));

		float yaw = mc.player.getYRot(), pitch = mc.player.getXRot(), yawO = mc.player.yRotO, pitchO = mc.player.xRotO;
		boolean anyResult = false;
		try {
			// The look the server already has: no turn at all.
			float sy = Myriad.rotations().serverYaw(), sp = Myriad.rotations().serverPitch();
			for (Candidate c : candidates) {
				if (strict && !looksAt(sy, sp, c.hit)) continue;
				BlockState r = simulate(item, stack, c.hit, pos, sy, sp, c.sneak);
				if (r != null) anyResult = true;
				if (r != null && expect.test(r)) return found(pos, c, Float.NaN, Float.NaN, false, Float.NaN, Float.NaN, wanted, count);
			}
			if (strict) {
				// Looking at the point clicked, steady against a slightly different look.
				Vec3 from = eyes.add(mc.player.getDeltaMovement());
				for (Candidate c : candidates) {
					float[] a = MathUtil.anglesTo(from, c.hit.getLocation());
					BlockState r = simulate(item, stack, c.hit, pos, a[0], a[1], c.sneak);
					if (r != null) anyResult = true;
					if (r == null || !expect.test(r) || !steady(item, stack, c.hit, pos, a[0], a[1], c.sneak, expect)) continue;
					return found(pos, c, a[0], a[1], true, Float.NaN, Float.NaN, wanted, count);
				}
				// No look at a point gives it: a clean rotation that does, looking at the point in the packet after. Not
				// while Grim checks every placement's own look (see PlacementManager.aimedAllowed).
				if (placement.aimedAllowed()) {
					for (float[] b : rotations(wanted)) {
						for (Candidate c : candidates) {
							if (!c.visible) continue;
							BlockState r = simulate(item, stack, c.hit, pos, b[0], b[1], c.sneak);
							if (r != null) anyResult = true;
							if (r == null || !expect.test(r)) continue;
							float[] a = MathUtil.anglesTo(from, c.hit.getLocation());
							return found(pos, c, b[0], b[1], false, a[0], a[1], wanted, count);
						}
					}
				}
			} else {
				for (float[] a : rotations(wanted)) {
					for (Candidate c : candidates) {
						BlockState r = simulate(item, stack, c.hit, pos, a[0], a[1], c.sneak);
						if (r != null) anyResult = true;
						if (r != null && expect.test(r)) return found(pos, c, a[0], a[1], false, Float.NaN, Float.NaN, wanted, count);
					}
				}
			}
		} finally {
			restore(yaw, pitch, yawO, pitchO);
		}
		// Nothing came out at all: the block can't stay there yet (a torch with nothing to hang on).
		return Solution.none(anyResult ? Check.WRONG_ANGLE : Check.NO_SUPPORT);
	}

	private Solution found(BlockPos pos, Candidate c, float yaw, float pitch, boolean lookAtHit, float aimYaw, float aimPitch, BlockState wanted, int count) {
		boolean air = c.hit.getBlockPos().equals(pos) && mc.level.getBlockState(pos).isAir();
		return Solution.of(new Click(pos, c.hit, c.sneak, air, yaw, pitch, lookAtHit, aimYaw, aimPitch, wanted, count));
	}

	/** The clean rotations tried when the look needn't be on the click: each heading, level, straight down and straight up. */
	private static List<float[]> rotations(BlockState wanted) {
		int headings = wanted.hasProperty(BlockStateProperties.ROTATION_16) ? 16 : 4;
		List<float[]> out = new ArrayList<>(headings * 3);
		ThreadLocalRandom r = ThreadLocalRandom.current();
		// Not quite straight up or down: core nudges the pitch to make a packet carry it, and past 90 Grim refuses the
		// packet (BadPacketsD), leaving the server with the look before.
		for (float pitch : new float[]{0, 89.9f, -89.9f}) {
			for (int i = 0; i < headings; i++) {
				// A few degrees off each time, well inside the facing it gives: Grim (DuplicateRotPlace, experimental) flags
				// placements turned into by exactly the same angle as the last, which clean headings would give every time.
				float yaw = 360f / headings * i + (float) r.nextDouble(-JITTER, JITTER);
				float p = pitch == 0 ? (float) r.nextDouble(-JITTER, JITTER) : pitch - Math.signum(pitch) * (float) r.nextDouble(0, JITTER);
				out.add(new float[]{yaw, p});
			}
		}
		return out;
	}

	private void restore(float yaw, float pitch, float yawO, float pitchO) {
		mc.player.setYRot(yaw);
		mc.player.setXRot(pitch);
		mc.player.yRotO = yawO;
		mc.player.xRotO = pitchO;
	}

	/** Whether looking along {@code yaw}/{@code pitch} from the eyes lands on {@code hit}'s face. */
	private static boolean looksAt(float yaw, float pitch, BlockHitResult hit) {
		BlockHitResult ray = Reach.rayHit(yaw, pitch, hit.getBlockPos(), 8);
		return ray != null && ray.getDirection() == hit.getDirection();
	}

	/** Whether the click still comes out right with the look off by {@link #TOLERANCE} each way. */
	private boolean steady(BlockItem item, ItemStack stack, BlockHitResult hit, BlockPos pos, float yaw, float pitch, boolean sneak, Expect expect) {
		for (float dy : new float[]{-TOLERANCE, TOLERANCE}) {
			for (float dp : new float[]{-TOLERANCE, TOLERANCE}) {
				BlockState r = simulate(item, stack, hit, pos, yaw + dy, pitch + dp, sneak);
				if (r == null || !expect.test(r)) return false;
			}
		}
		return true;
	}

	/** {@link #simulate(BlockItem, ItemStack, BlockHitResult, BlockPos, float, float)}, sneaking if {@code sneak} (client only, nothing sent). */
	private @Nullable BlockState simulate(BlockItem item, ItemStack stack, BlockHitResult hit, BlockPos pos, float yaw, float pitch, boolean sneak) {
		return sneak ? sneaking(() -> simulate(item, stack, hit, pos, yaw, pitch)) : simulate(item, stack, hit, pos, yaw, pitch);
	}

	/** What placing {@code stack} with {@code hit} while looking along {@code yaw}/{@code pitch} would put at {@code pos}. */
	private @Nullable BlockState simulate(BlockItem item, ItemStack stack, BlockHitResult hit, BlockPos pos, float yaw, float pitch) {
		mc.player.setYRot(yaw);
		mc.player.setXRot(Math.clamp(pitch, -90, 90));
		mc.player.yRotO = mc.player.getYRot();
		mc.player.xRotO = mc.player.getXRot();
		BlockPlaceContext ctx = new BlockPlaceContext(mc.player, InteractionHand.MAIN_HAND, stack, hit);
		if (!ctx.canPlace()) return null;
		BlockPlaceContext updated = item.updatePlacementContext(ctx);
		// Some items move the placement elsewhere (scaffolding along a column, signs onto a hanging sign).
		if (updated == null || !updated.getClickedPos().equals(pos)) return null;
		return ((BlockItemAccessor) item).myriad$placementState(updated);
	}

	/** What {@code simulation} gives with the sneak key down on the client alone, for working out clicks made sneaking. */
	private <T> T sneaking(Supplier<T> simulation) {
		LocalPlayer p = mc.player;
		Input real = p.input.keyPresses;
		p.input.keyPresses = new Input(real.forward(), real.backward(), real.left(), real.right(), real.jump(), true, real.sprint());
		try {
			return simulation.get();
		} finally {
			p.input.keyPresses = real;
		}
	}

	/** Whether where on a face the click lands decides part of {@code wanted}: a half (stairs, slabs, trapdoors) or a door's hinge. */
	private static boolean positional(BlockState wanted) {
		return wanted.hasProperty(BlockStateProperties.HALF) || wanted.hasProperty(BlockStateProperties.SLAB_TYPE) || wanted.hasProperty(BlockStateProperties.DOOR_HINGE);
	}
}
