package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.build.Blueprint;
import dev.myriadclient.api.build.Build;
import dev.myriadclient.api.build.Build.Status;
import dev.myriadclient.api.build.Target;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.service.Breaking;
import dev.myriadclient.api.service.Building;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.util.MathUtil;
import dev.myriadclient.api.util.Reach;
import dev.myriadclient.impl.mixin.BlockItemAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Plans and runs builds. Each tick, for every running build, only the blueprint positions in reach are looked at:
 * each gets a {@link Status} from cheap checks, breaks are handed to {@link Breaking} (top down, nearest first), and
 * the best few placements (bottom up, nearest first) get the full placement check and are placed through
 * {@link Placement}. Whether the whole blueprint is done is checked at most every few seconds, and only once nothing
 * in reach is left to do.
 */
public final class BuildManager implements Building {
	/** Most breaks handed to the breaking service per build per tick; the rest wait, so ordering stays fresh. */
	private static final int BREAKS_QUEUED = 8;
	/** Most placement candidates given the full (raycasting) check per build per tick. */
	private static final int PLACES_CHECKED = 24;
	/** Ticks between full "is the blueprint done" scans. */
	private static final int SCAN_INTERVAL = 100;

	private final Minecraft mc = Minecraft.getInstance();
	private final List<BuildImpl> builds = new ArrayList<>();
	private boolean movedMaterialThisTick;

	private final class BuildImpl implements Build {
		final Object owner;
		final Blueprint blueprint;
		final Options options;
		final CompletableFuture<Boolean> finished = new CompletableFuture<>();
		volatile List<Step> steps = List.of();
		volatile int remaining = -1;
		volatile boolean running = true, done;
		int lastScan = -SCAN_INTERVAL, age;

		BuildImpl(Object owner, Blueprint blueprint, Options options) {
			this.owner = owner;
			this.blueprint = blueprint;
			this.options = options;
		}

		@Override
		public List<Step> steps() {
			return steps;
		}

		@Override
		public int remaining() {
			return remaining;
		}

		@Override
		public boolean isDone() {
			return done;
		}

		@Override
		public boolean isRunning() {
			return running;
		}

		@Override
		public void stop() {
			end(false);
		}

		void end(boolean completed) {
			if (!running) return;
			running = false;
			Myriad.breaking().cancel(this);
			Myriad.placement().cancel(this);
			finished.complete(completed);
		}

		@Override
		public CompletableFuture<Boolean> finished() {
			return finished;
		}
	}

	@Override
	public Build start(Object owner, Blueprint blueprint, Options options) {
		BuildImpl b = new BuildImpl(owner, blueprint, options);
		builds.add(b);
		return b;
	}

	@Override
	public void stop(Object owner) {
		for (BuildImpl b : new ArrayList<>(builds)) if (b.owner == owner) b.stop();
	}

	@Override
	public List<Build> running() {
		return builds.stream().filter(Build::isRunning).map(Build.class::cast).toList();
	}

	/** Before the breaking and placement services run this tick, so what's planned now happens now. */
	@Subscribe(priority = Priority.HIGH)
	private void onTick(TickEvent.Pre e) {
		builds.removeIf(b -> !b.running);
		if (builds.isEmpty() || mc.player == null || mc.level == null) return;
		movedMaterialThisTick = false;
		for (BuildImpl b : new ArrayList<>(builds)) {
			try {
				tick(b);
			} catch (RuntimeException ex) {
				dev.myriadclient.impl.MyriadImpl.LOG.error("Build for {} failed", b.owner, ex);
				b.stop();
			}
		}
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		for (BuildImpl b : new ArrayList<>(builds)) b.stop();
		builds.clear();
	}

	private void tick(BuildImpl b) {
		b.age++;
		Options o = b.options;
		Vec3 eyes = mc.player.getEyePosition();
		double reach = Math.max(o.placing().range(), o.breaking().range());

		Map<BlockPos, Status> status = new LinkedHashMap<>();
		Map<BlockPos, Target> targets = new LinkedHashMap<>();
		List<BlockPos> breaks = new ArrayList<>(), places = new ArrayList<>();
		b.blueprint.near(eyes, reach + 1, (pos, target) -> {
			if (!mc.level.isLoaded(pos)) return;
			Status s = evaluate(pos, target, eyes, o);
			status.put(pos, s);
			targets.put(pos, target);
			if (s == Status.BREAK) breaks.add(pos);
			else if (s == Status.PLACE) places.add(pos);
		});

		// Top down, so falling blocks land in space that's already clear; then nearest first.
		breaks.sort(Comparator.<BlockPos>comparingInt(p -> -p.getY()).thenComparingDouble(p -> eyes.distanceToSqr(Vec3.atCenterOf(p))));
		for (int i = 0; i < breaks.size() && i < BREAKS_QUEUED; i++) {
			Breaking.Attempt a = Myriad.breaking().breakBlock(b, breaks.get(i), o.priority(), o.breaking());
			if (a.accepted()) status.put(breaks.get(i), Status.WAITING);
		}

		// Bottom up, so each placement has something under or beside it; then nearest first.
		places.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingDouble(p -> eyes.distanceToSqr(Vec3.atCenterOf(p))));
		int placed = 0, checked = 0;
		for (BlockPos pos : places) {
			if (placed >= o.placesPerTick() || checked >= PLACES_CHECKED) break;
			checked++;
			Status s = place(b, pos, targets.get(pos));
			status.put(pos, s);
			if (s == Status.WAITING) placed++;
		}

		List<Build.Step> steps = new ArrayList<>(status.size());
		boolean workLeft = false;
		for (Map.Entry<BlockPos, Status> e : status.entrySet()) {
			steps.add(new Build.Step(e.getKey(), targets.get(e.getKey()), e.getValue()));
			if (e.getValue() != Status.DONE) workLeft = true;
		}
		b.steps = steps;

		// Whole-blueprint check, only when there's nothing left to do here.
		if (!workLeft && b.age - b.lastScan >= SCAN_INTERVAL) {
			b.lastScan = b.age;
			int[] left = {0};
			b.blueprint.forEach((pos, target) -> {
				if (!mc.level.isLoaded(pos) || !target.matches(mc.level, pos, mc.level.getBlockState(pos))) left[0]++;
			});
			b.remaining = left[0];
			b.done = left[0] == 0;
			if (b.done && !o.keepUp()) b.end(true);
		} else if (workLeft) {
			b.done = false;
		}
	}

	/** The cheap part of planning a position: everything but the placement's raycasts and material. */
	private Status evaluate(BlockPos pos, Target target, Vec3 eyes, Options o) {
		BlockState state = mc.level.getBlockState(pos);
		if (target.matches(mc.level, pos, state)) return Status.DONE;
		if (Myriad.breaking().isPending(pos) || Myriad.placement().isPending(pos)) return Status.WAITING;
		double dist = eyes.distanceToSqr(Vec3.atCenterOf(pos));
		boolean inTheWay = target.wantsAir() || !state.canBeReplaced();
		if (inTheWay) {
			if (!target.wantsAir() && !o.breakWrong()) return Status.BLOCKED;
			if (!Reach.canReach(pos, o.breaking().range())) return Status.OUT_OF_RANGE;
			switch (Myriad.breaking().check(pos, o.breaking())) {
				case UNBREAKABLE -> {
					return Status.UNBREAKABLE;
				}
				case OUT_OF_RANGE -> {
					return Status.OUT_OF_RANGE;
				}
				case PENDING, UNAVAILABLE -> {
					return Status.WAITING;
				}
				case NOTHING_THERE -> {
					// A fluid: nothing to break; a non-air target places into it below.
					if (target.wantsAir()) return Status.DONE;
				}
				case OK -> {
					if (supportsPlayer(pos)) return Status.SUPPORTS_YOU;
					if (o.avoidFluids() && holdsBackFluid(pos)) return Status.FLUID;
					return Status.BREAK;
				}
			}
		}
		if (dist > o.placing().range() * o.placing().range()) return Status.OUT_OF_RANGE;
		return Status.PLACE;
	}

	/** Gets the material and places, or says why it couldn't. */
	private Status place(BuildImpl b, BlockPos pos, Target target) {
		int slot = materialSlot(target);
		if (slot == -2) return Status.NO_ITEM;
		if (slot < 0) return Status.WAITING;
		Placement.Options po = b.options.placing();
		Placement.Attempt attempt;
		BlockState oriented = target.orientedState();
		if (oriented != null) {
			Placement.Check check = Myriad.placement().check(pos, po);
			if (!check.ok()) return status(check);
			BlockHitResult hit = orientedClick(pos, oriented, slot, po);
			if (hit == null) return Status.WRONG_ANGLE;
			// The orientation follows the rotation, so it's always sent.
			Placement.Options rotated = po.withRotate(true);
			attempt = Myriad.placement().place(b, hit, slot, rotated);
		} else {
			attempt = Myriad.placement().place(b, pos, slot, po);
		}
		return attempt.sent() ? Status.WAITING : status(attempt.check());
	}

	private static Status status(Placement.Check check) {
		return switch (check) {
			case OK -> Status.PLACE;
			case OCCUPIED -> Status.BLOCKED;
			case OUT_OF_RANGE -> Status.OUT_OF_RANGE;
			case ENTITY_IN_WAY -> Status.ENTITY_IN_WAY;
			case NO_SUPPORT -> Status.NO_SUPPORT;
			case NOT_VISIBLE -> Status.NOT_VISIBLE;
			case UNAVAILABLE, PENDING, RATE_LIMITED -> Status.WAITING;
		};
	}

	/**
	 * The hotbar slot with the best item for {@code target}; otherwise moves one in from the inventory (one move a
	 * tick) and returns -1 to wait for it, or -2 if there's none at all.
	 */
	private int materialSlot(Target target) {
		var inv = mc.player.getInventory();
		int best = -1, bestPref = Integer.MAX_VALUE;
		for (int i = 0; i < 9; i++) {
			int pref = target.preference(inv.getItem(i));
			if (pref >= 0 && pref < bestPref) {
				best = i;
				bestPref = pref;
			}
		}
		if (best >= 0) return best;
		if (Myriad.inventory().findInInventory(s -> target.preference(s) >= 0) < 0) return -2;
		// Grim cancels inventory clicks while you move: keys are released for a tick first if needed.
		if (!movedMaterialThisTick && Myriad.inventory().prepareClick()) {
			movedMaterialThisTick = true;
			Myriad.inventory().ensureInHotbar(s -> target.preference(s) >= 0, -1);
		}
		return -1;
	}

	/**
	 * A click that places {@code oriented} the right way: for each face it could be placed against and a few points
	 * on it, what vanilla would place with the rotation that looks at that point. The rotation is nudged a few
	 * degrees both ways to make sure it isn't on an edge where a small error flips the result.
	 */
	private BlockHitResult orientedClick(BlockPos pos, BlockState oriented, int slot, Placement.Options po) {
		ItemStack stack = mc.player.getInventory().getItem(slot);
		if (!(stack.getItem() instanceof BlockItem item)) return null;
		Vec3 eyes = mc.player.getEyePosition();
		List<Property<?>> props = Target.Orientation.PROPERTIES.stream().filter(oriented::hasProperty).toList();
		float yaw = mc.player.getYRot(), pitch = mc.player.getXRot(), yawO = mc.player.yRotO, pitchO = mc.player.xRotO;
		try {
			for (BlockHitResult face : Myriad.placement().clickTargets(pos)) {
				if (po.visibleFaces() && !Reach.faceExposed(face.getBlockPos(), face.getDirection())) continue;
				for (Vec3 point : pointsOn(face)) {
					BlockHitResult hit = new BlockHitResult(point, face.getDirection(), face.getBlockPos(), false);
					float[] rot = MathUtil.anglesTo(eyes, point);
					if (places(item, stack, hit, rot[0], rot[1], oriented, props)
						&& places(item, stack, hit, rot[0] + 3, rot[1] + 3, oriented, props)
						&& places(item, stack, hit, rot[0] - 3, rot[1] - 3, oriented, props)) return hit;
				}
			}
			return null;
		} finally {
			mc.player.setYRot(yaw);
			mc.player.setXRot(pitch);
			mc.player.yRotO = yawO;
			mc.player.xRotO = pitchO;
		}
	}

	/** Whether placing with {@code hit} while facing {@code yaw}/{@code pitch} gives {@code oriented}'s orientation. */
	private boolean places(BlockItem item, ItemStack stack, BlockHitResult hit, float yaw, float pitch, BlockState oriented, List<Property<?>> props) {
		mc.player.setYRot(yaw);
		mc.player.setXRot(Math.clamp(pitch, -90, 90));
		mc.player.yRotO = mc.player.getYRot();
		mc.player.xRotO = mc.player.getXRot();
		BlockState result = ((BlockItemAccessor) item).myriad$placementState(new BlockPlaceContext(mc.player, InteractionHand.MAIN_HAND, stack, hit));
		if (result == null || !result.is(oriented.getBlock())) return false;
		for (Property<?> p : props) if (!result.getValue(p).equals(oriented.getValue(p))) return false;
		return true;
	}

	/** The face's centre, and points towards its edges for blocks that care where they're clicked (slabs, stairs). */
	private static List<Vec3> pointsOn(BlockHitResult face) {
		Vec3 c = face.getLocation();
		List<Vec3> points = new ArrayList<>(5);
		points.add(c);
		if (face.getDirection().getAxis() == Direction.Axis.Y) {
			points.add(c.add(0.3, 0, 0));
			points.add(c.add(-0.3, 0, 0));
			points.add(c.add(0, 0, 0.3));
			points.add(c.add(0, 0, -0.3));
		} else {
			points.add(c.add(0, 0.3, 0));
			points.add(c.add(0, -0.3, 0));
		}
		return points;
	}

	private boolean supportsPlayer(BlockPos pos) {
		return mc.player.onGround() && !mc.player.getAbilities().flying && pos.equals(mc.player.getOnPos());
	}

	/** A fluid above or beside would flow into the space. */
	private boolean holdsBackFluid(BlockPos pos) {
		for (Direction d : Direction.values()) {
			if (d == Direction.DOWN) continue;
			if (!mc.level.getFluidState(pos.relative(d)).isEmpty()) return true;
		}
		return false;
	}
}
