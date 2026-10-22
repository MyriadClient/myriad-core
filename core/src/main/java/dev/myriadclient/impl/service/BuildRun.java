package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.build.BlockRules;
import dev.myriadclient.api.build.Blueprint;
import dev.myriadclient.api.build.Build;
import dev.myriadclient.api.build.Target;
import dev.myriadclient.api.service.Breaking;
import dev.myriadclient.api.service.Building;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.util.MathUtil;
import dev.myriadclient.api.util.Reach;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * One running build: each tick, every blueprint position in reach that isn't done gets a {@link Build.Status}; then
 * wrong blocks are broken (top down), blocks are placed (with a {@link Placement#solve solved} click where the target
 * cares how they come out), more of a block is added where an exact target holds more (candles, layers, slab halves),
 * and blocks are clicked into their setting (repeater delay, comparator mode, note, open).
 *
 * <h2>Order</h2>
 * Blocks that come out the same however they're clicked (stone, glass, planks) go first, then the ones that face a way
 * or sit on a half (stairs, slabs, repeaters, doors): by then they have more faces to be placed against, so more of
 * them can be clicked from where you stand. Within each, bottom up so each has something under it, then nearest first.
 * A block that couldn't be placed is tried again when you move or a block next to it changes.
 *
 * <h2>Cost</h2>
 * A tick reads what's in reach with no allocation for positions that are done: positions travel as packed longs in
 * lists kept from tick to tick, and only unfinished ones are kept. Comparing a block with an exact target reads only
 * the properties that block has ({@link BlockRules}). Solving clicks, the expensive step, stops at
 * {@link #SOLVE_BUDGET_NANOS} a tick and isn't repeated for a position that just failed until something changes.
 */
final class BuildRun implements Build {
	/** Most breaks handed to the breaking service per tick; the rest wait, so the order stays fresh. */
	private static final int BREAKS_QUEUED = 8;
	/** Most time spent solving clicks in one tick; the rest wait for the next, in order. */
	private static final long SOLVE_BUDGET_NANOS = 1_000_000;
	/** Ticks between checks of the whole blueprint (finite builds), made only when nothing in reach is left to do. */
	private static final int SCAN_INTERVAL = 100;
	/** Ticks a position no click from here places stays unsearched, unless you move or a neighbour changes. */
	private static final int HOLD_TICKS = 20;
	/** Times a block this build placed and found wrong is broken and placed again before it's left as it is. */
	private static final int REPAIRS = 3;
	/** Refusals by the server at one position before it's left alone for a while, and how long that is in ticks. */
	private static final int REFUSALS = 3, REFUSED_TICKS = 200;
	/** Ticks without acting after the server moves you. */
	private static final int TELEPORT_PAUSE_TICKS = 5;
	private static final Status[] STATUSES = Status.values();

	/** What's to be done at a position, beside the status shown for it. */
	private static final byte NOTHING = 0, BREAK = 1, PLACE = 2, GROW = 3, CLICK = 4;
	/** What planning the position at hand found to do (a field, not a result, so planning allocates nothing). */
	private byte kind;

	private final Minecraft mc = Minecraft.getInstance();
	private final BuildManager manager;
	final Object owner;
	private final Blueprint blueprint;
	private final Building.Options options;
	private final CompletableFuture<Boolean> finished = new CompletableFuture<>();

	private final Long2IntOpenHashMap refusals = new Long2IntOpenHashMap(), refusedUntil = new Long2IntOpenHashMap(), heldUntil = new Long2IntOpenHashMap();
	private final Long2ObjectOpenHashMap<Status> heldStatus = new Long2ObjectOpenHashMap<>();
	/** Positions this build placed a block at, and how often it has had to redo each. */
	private final LongOpenHashSet placed = new LongOpenHashSet();
	private final Long2IntOpenHashMap repairs = new Long2IntOpenHashMap();

	// This tick's unfinished positions, kept between ticks so planning allocates nothing for positions that are done.
	private final LongArrayList work = new LongArrayList();
	private final List<Target> workTargets = new ArrayList<>();
	private final ByteArrayList workStatus = new ByteArrayList(), workKind = new ByteArrayList();
	/** Each kind's positions as sort key and index packed in one long ({@link #key}), so they sort as plain numbers. */
	private final LongArrayList breaks = new LongArrayList(), places = new LongArrayList(), clicks = new LongArrayList();
	private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
	private final BuildMaterials materials;

	private volatile List<Step> steps = List.of();
	private int stepsTick = -1;
	private @Nullable LevelChunk chunk;
	private int chunkX, chunkZ;
	private volatile int remaining = -1;
	private volatile boolean running = true, done;
	private int pausedUntil;
	/** This build's breaks not answered yet. */
	private int breaking;
	private int tick, lastScan = -SCAN_INTERVAL;
	private long lastFeet = Long.MIN_VALUE;

	BuildRun(BuildManager manager, Object owner, Blueprint blueprint, Building.Options options) {
		this.manager = manager;
		this.owner = owner;
		this.blueprint = blueprint;
		this.options = options;
		materials = new BuildMaterials(manager);
	}

	// ---- Build --------------------------------------------------------------------------------------------------

	@Override
	public List<Step> steps() {
		if (stepsTick != tick && running) {
			stepsTick = tick;
			List<Step> out = new ArrayList<>(work.size());
			for (int i = 0; i < work.size(); i++) out.add(new Step(BlockPos.of(work.getLong(i)), workTargets.get(i), status(i)));
			steps = out;
		}
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

	@Override
	public CompletableFuture<Boolean> finished() {
		return finished;
	}

	void end(boolean completed) {
		if (!running) return;
		running = false;
		Myriad.breaking().cancel(this);
		Myriad.placement().cancel(this);
		steps = List.of();
		chunk = null;
		finished.complete(completed);
	}

	// ---- told by the manager ------------------------------------------------------------------------------------

	/** The server sent the block at {@code pos}: its neighbours may now have a face to be placed against. */
	void blockUpdated(BlockPos pos) {
		if (heldUntil.isEmpty()) return;
		heldUntil.remove(pos.asLong());
		for (Direction d : Direction.values()) heldUntil.remove(BlockPos.offset(pos.asLong(), d));
	}

	/** The server moved you: it may have set your look too; nothing's clicked until the look is ours again. */
	void teleported() {
		pausedUntil = tick + TELEPORT_PAUSE_TICKS;
	}

	// ---- planning -----------------------------------------------------------------------------------------------

	void tick() {
		tick++;
		if (!refusedUntil.isEmpty()) refusedUntil.values().removeIf(until -> until <= tick);
		long feet = mc.player.blockPosition().asLong();
		if (feet != lastFeet || mc.player.getDeltaMovement().lengthSqr() > 0.01) {
			// From somewhere else, other clicks may work.
			lastFeet = feet;
			heldUntil.clear();
			heldStatus.clear();
		}
		if (tick < pausedUntil) return;
		// Not while you're in a chest or your inventory, or eating, drinking or drawing a bow.
		if (mc.gui.screen() instanceof AbstractContainerScreen<?> || mc.player.isUsingItem()) return;
		plan(options);
		act(options);
		checkDone(options);
	}

	/** Every position in reach that isn't done: its status, and what to do there, sorted into the kinds' lists. */
	private void plan(Building.Options o) {
		Vec3 eyes = mc.player.getEyePosition();
		double reach = Math.max(o.placing().range(), o.breaking().range());
		work.clear();
		workTargets.clear();
		workStatus.clear();
		workKind.clear();
		breaks.clear();
		places.clear();
		clicks.clear();
		chunk = null;
		blueprint.near(eyes, reach + 1, (pos, target) -> {
			BlockState current = blockAt(pos);
			// The same state object: done, and by far the commonest case once something's built.
			if (current == null || target instanceof Target.Exact exact && current == exact.state()) return;
			kind = NOTHING;
			Status s = target instanceof Target.Exact exact ? evaluate(pos, current, exact.state(), o) : evaluate(pos, current, target, o);
			if (s == Status.DONE) return;
			// Something to do here, unless a placement or break of it is still waiting for the server (asked only now:
			// most positions in reach have nothing to do, and these go through the services' routers).
			if (kind != NOTHING && pending(pos)) {
				s = Status.WAITING;
				kind = NOTHING;
			}
			int i = work.size();
			work.add(pos.asLong());
			workTargets.add(target);
			workStatus.add((byte) s.ordinal());
			workKind.add(kind);
			int distance = (int) (eyes.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) * 256);
			int height = Math.clamp(pos.getY() + 2048, 0, 4095);
			switch (kind) {
				// Top down, so falling blocks land in space that's already clear; then nearest first.
				case BREAK -> breaks.add(key((long) (4095 - height) << 24 | distance, i));
				// Blocks the click doesn't shape first, then the others; bottom up; nearest first.
				case PLACE, GROW -> {
					BlockState shaped = target.orientedState();
					places.add(key((shaped != null && BlockRules.oriented(shaped) ? 1L << 36 : 0) | (long) height << 24 | distance, i));
				}
				case CLICK -> clicks.add(key(distance, i));
				default -> {
				}
			}
		});
	}

	/** Breaks first (top down), then placements, then clicks into a setting, within this tick's share. */
	private void act(Building.Options o) {
		// The look after a solved click goes out this tick: a break's turn now would take its place.
		boolean follow = manager.placement().followPending();
		int actions = 0;
		sort(breaks);
		for (int k = 0, queued = 0; k < breaks.size() && queued < BREAKS_QUEUED && !follow; k++) {
			int i = index(breaks.getLong(k));
			Breaking.Attempt a = Myriad.breaking().breakBlock(this, at(i), o.priority(), o.breaking());
			if (a.accepted()) {
				if (placed.remove(work.getLong(i))) repairs.addTo(work.getLong(i), 1);
				breaking++;
				a.result().whenComplete((ok, t) -> breaking--);
				set(i, Status.WAITING);
				queued++;
			}
		}

		// While a break is going on, its look is the breaking service's: turning for clicks now would pull it away (Grim, RotationBreak).
		if (breaking > 0 && o.breaking().rotate()) {
			places.clear();
			clicks.clear();
		}
		sort(places);
		long deadline = System.nanoTime() + SOLVE_BUDGET_NANOS;
		for (int k = 0; k < places.size() && actions < o.placesPerTick() && System.nanoTime() < deadline; k++) {
			int i = index(places.getLong(k));
			Status s = place(at(i), workTargets.get(i), o);
			set(i, s);
			if (s == Status.WAITING) actions++;
		}

		// One step per click, the next once the server has answered the last (some only change on the server).
		sort(clicks);
		for (int k = 0; k < clicks.size() && actions < o.placesPerTick(); k++) {
			int i = index(clicks.getLong(k));
			Status s = click(at(i).immutable(), ((Target.Exact) workTargets.get(i)).state(), o);
			set(i, s);
			if (s == Status.WAITING) actions++;
		}
	}

	/** Whether the build is done: by what's in reach for one that keeps up, else by the whole blueprint now and then. */
	private void checkDone(Building.Options o) {
		boolean workLeft = !work.isEmpty();
		// The whole blueprint, for builds that finish, only when there's nothing left to do here.
		if (o.keepUp()) {
			done = !workLeft;
		} else if (!workLeft && tick - lastScan >= SCAN_INTERVAL) {
			lastScan = tick;
			int[] left = {0};
			blueprint.forEach((pos, target) -> {
				if (!mc.level.isLoaded(pos) || !matches(pos, mc.level.getBlockState(pos), target, o)) left[0]++;
			});
			remaining = left[0];
			done = left[0] == 0;
			if (done) end(true);
		} else if (workLeft) {
			done = false;
		}
	}

	/** Whether {@code pos} is done as {@code target} wants (settings counted only with {@link Building.Options#clickSettings()}). */
	private boolean matches(BlockPos pos, BlockState current, Target target, Building.Options o) {
		if (target instanceof Target.Exact exact) {
			BlockState want = exact.state();
			if (BlockRules.placedWithNeighbour(want)) return true;
			return BlockRules.matches(current, want, o.clickSettings());
		}
		return target.matches(mc.level, pos, current);
	}

	/** The cheap part of planning a position with an exact target: everything but solving the click and the item. */
	private Status evaluate(BlockPos pos, BlockState current, BlockState want, Building.Options o) {
		// States are shared objects: the same one is the commonest answer, and the cheapest.
		if (current == want) return Status.DONE;
		if (BlockRules.placedWithNeighbour(want)) {
			// Comes with the block below or beside it; only something else in the way matters.
			if (current.is(want.getBlock()) || current.canBeReplaced()) return Status.DONE;
			return wrong(pos, current, false, o);
		}
		if (BlockRules.matches(current, want, o.clickSettings())) return Status.DONE;
		if (!refusedUntil.isEmpty() && refusedUntil.containsKey(pos.asLong())) return Status.REFUSED;
		if (want.isAir()) return wrong(pos, current, true, o);
		if (current.is(want.getBlock())) {
			if (!BlockRules.placedMatches(current, want)) return wrong(pos, current, false, o);
			int have = BlockRules.count(current), need = BlockRules.count(want);
			if (have > need) return wrong(pos, current, false, o);
			if (have < need) return reachable(pos, o, GROW);
			int steps = BlockRules.clicksToMatch(current, want);
			if (steps < 0) return Status.UNSUPPORTED;
			if (steps == 0) return Status.DONE;
			kind = CLICK;
			return Status.PLACE;
		}
		if (!current.canBeReplaced()) return wrong(pos, current, false, o);
		if (!BlockRules.placeable(want)) return Status.UNSUPPORTED;
		if (BlockRules.falls(mc.level, pos, want)) return Status.NO_SUPPORT;
		return reachable(pos, o, PLACE);
	}

	/** The cheap part of planning a position with any other target. */
	private Status evaluate(BlockPos pos, BlockState current, Target target, Building.Options o) {
		if (target.matches(mc.level, pos, current)) return Status.DONE;
		if (!refusedUntil.isEmpty() && refusedUntil.containsKey(pos.asLong())) return Status.REFUSED;
		if (target.wantsAir() || !current.canBeReplaced()) return wrong(pos, current, target.wantsAir(), o);
		return reachable(pos, o, PLACE);
	}

	private Status reachable(BlockPos pos, Building.Options o, byte what) {
		if (!Reach.canReach(pos, o.placing().range())) return Status.OUT_OF_RANGE;
		kind = what;
		return Status.PLACE;
	}

	/**
	 * Something other than the target is there: broken if the target wants the space empty, if the build breaks wrong
	 * blocks, or if it's a block this build placed that came out wrong (its own to redo, a few times); else blocked.
	 */
	private Status wrong(BlockPos pos, BlockState current, boolean wantsAir, Building.Options o) {
		boolean own = placed.contains(pos.asLong()) && repairs.get(pos.asLong()) < REPAIRS;
		if (!wantsAir && !o.breakWrong() && !own) return Status.BLOCKED;
		if (current.isAir() || !current.getFluidState().isEmpty() && current.canBeReplaced()) return Status.DONE;
		if (mc.player.onGround() && !mc.player.getAbilities().flying && pos.equals(mc.player.getOnPos())) return Status.SUPPORTS_YOU;
		return switch (Myriad.breaking().check(pos, o.breaking())) {
			case OK -> {
				if (o.avoidFluids() && holdsBackFluid(pos)) yield Status.FLUID;
				kind = BREAK;
				yield Status.BREAK;
			}
			case OUT_OF_RANGE -> Status.OUT_OF_RANGE;
			case PENDING, UNAVAILABLE -> Status.WAITING;
			case NOTHING_THERE -> Status.DONE;
			case UNBREAKABLE -> Status.UNBREAKABLE;
		};
	}

	private boolean pending(BlockPos pos) {
		return Myriad.placement().isPending(pos) || Myriad.breaking().isPending(pos);
	}

	/** A fluid above or beside would flow into the space. */
	private boolean holdsBackFluid(BlockPos pos) {
		for (Direction d : Direction.values()) {
			if (d != Direction.DOWN && !mc.level.getFluidState(pos.relative(d)).isEmpty()) return true;
		}
		return false;
	}

	// ---- acting -------------------------------------------------------------------------------------------------

	/** Gets the item, solves the click where the target shapes the block, and places, or says why not. */
	private Status place(BlockPos at, Target target, Building.Options o) {
		Status held = held(at);
		if (held != null) return held;
		BlockState want = target.orientedState();
		int slot = target instanceof Target.Exact exact ? materials.slotFor(BlockRules.item(exact.state())) : materials.slotFor(target);
		if (slot == -2) return Status.NO_ITEM;
		if (slot == -3) return Status.NO_SLOT;
		if (slot < 0 || !Myriad.placement().canPlaceWith(slot)) return Status.WAITING;
		// Only now a position of its own: asking above is for every position in reach each tick, and allocates nothing.
		BlockPos pos = at.immutable();
		if (want == null) {
			Placement.Attempt a = Myriad.placement().place(this, pos, slot, o.placing());
			if (!a.sent()) return status(a.check());
			track(pos, a);
			return Status.WAITING;
		}
		Placement.Solution solution = Myriad.placement().solve(pos, want, slot, o.placing());
		if (!solution.found()) return hold(pos, status(solution.check()));
		Placement.Click click = solution.click();
		Placement.Attempt a = Myriad.placement().place(this, click, slot, o.placing());
		if (!a.sent()) return status(a.check());
		if (!click.turns()) {
			track(pos, a);
			return Status.WAITING;
		}
		// A turned click that comes to nothing mostly found the look different from the plan, or never got it: left
		// until something changes rather than turned for every tick (not counted as the server refusing it).
		placed.add(pos.asLong());
		a.result().thenAccept(ok -> {
			if (!ok) hold(pos, Status.WRONG_ANGLE);
		});
		return Status.WAITING;
	}

	/** Clicks the block by hand one step towards the target's setting. */
	private Status click(BlockPos pos, BlockState want, Building.Options o) {
		Status held = held(pos);
		if (held != null) return held;
		// Not while a block is in the off hand for air placements: the hand would click with what's there.
		if (Myriad.placement().offHandSlot() >= 0) return Status.WAITING;
		BlockState current = mc.level.getBlockState(pos);
		boolean rotate = o.placing().rotate();
		// Looking at it, from where the look keeps it facing the way it does (a fence gate swings away from you).
		Vec3 from = mc.player.getEyePosition().add(mc.player.getDeltaMovement());
		Predicate<Vec3> keeps = p -> BlockRules.clickKeepsFacing(current, MathUtil.anglesTo(from, p)[0]);
		BlockHitResult hit = handHit(pos, o.placing().visibleFaces(), o.placing().range(), keeps);
		if (hit == null) {
			if (handHit(pos, o.placing().visibleFaces(), o.placing().range(), p -> true) != null) return hold(pos, Status.WRONG_ANGLE);
			return Reach.canReach(pos, o.placing().range()) ? Status.NOT_VISIBLE : Status.OUT_OF_RANGE;
		}
		// Without turning, unless the look you have would swing it round.
		boolean turn = rotate || !BlockRules.clickKeepsFacing(current, Myriad.rotations().serverYaw());
		Placement.Attempt a = Myriad.placement().use(this, hit, -1, o.placing().withRotate(turn).withSneak(false));
		return a.sent() ? Status.WAITING : status(a.check());
	}

	/**
	 * Where to click the block at {@code pos} by hand: the nearest point of its outline you can see within
	 * {@code range} (any point unless {@code mustSee}) that {@code allowed} accepts, the middle of a part first; null if none.
	 */
	private @Nullable BlockHitResult handHit(BlockPos pos, boolean mustSee, double range, Predicate<Vec3> allowed) {
		Vec3 eyes = mc.player.getEyePosition();
		double r2 = (range - 0.05) * (range - 0.05);
		BlockHitResult best = null, hidden = null;
		double bestD = Double.MAX_VALUE, hiddenD = Double.MAX_VALUE;
		for (Direction face : Direction.values()) {
			List<Vec3> points = Reach.clickPoints(pos, face, false);
			for (int i = 0; i < points.size(); i++) {
				Vec3 p = points.get(i);
				if (eyes.distanceToSqr(p) > r2 || !allowed.test(p)) continue;
				// A part's middle (each part's first point) first: a look a little off still lands on it.
				double d = eyes.distanceToSqr(p) + (i % 2 == 0 ? 0 : 0.25);
				BlockHitResult hit = new BlockHitResult(p, face, pos, false);
				if (Reach.canSee(hit)) {
					if (d < bestD) {
						best = hit;
						bestD = d;
					}
				} else if (d < hiddenD) {
					hidden = hit;
					hiddenD = d;
				}
			}
		}
		return best != null || mustSee ? best : hidden;
	}

	private static Status status(Placement.Check check) {
		return switch (check) {
			case OK -> Status.PLACE;
			case OCCUPIED -> Status.BLOCKED;
			case OUT_OF_RANGE -> Status.OUT_OF_RANGE;
			case ENTITY_IN_WAY -> Status.ENTITY_IN_WAY;
			case NO_SUPPORT -> Status.NO_SUPPORT;
			case NOT_VISIBLE -> Status.NOT_VISIBLE;
			case WRONG_ANGLE -> Status.WRONG_ANGLE;
			case UNAVAILABLE, PENDING, RATE_LIMITED -> Status.WAITING;
		};
	}

	/** Follows a placement's answer: a few refusals in a row leave the position alone for a while. */
	private void track(BlockPos pos, Placement.Attempt attempt) {
		long key = pos.asLong();
		placed.add(key);
		attempt.result().thenAccept(ok -> {
			if (ok) {
				refusals.remove(key);
			} else if (refusals.addTo(key, 1) + 1 >= REFUSALS) {
				refusals.remove(key);
				refusedUntil.put(key, tick + REFUSED_TICKS);
			}
		});
	}

	/** Why {@code pos} couldn't be placed when last tried, if that was recent and nothing has changed since. */
	private @Nullable Status held(BlockPos pos) {
		if (heldUntil.isEmpty()) return null;
		long key = pos.asLong();
		return heldUntil.get(key) > tick ? heldStatus.get(key) : null;
	}

	/** Remembers that {@code pos} can't be placed from here, so it isn't searched again every tick. */
	private Status hold(BlockPos pos, Status why) {
		long key = pos.asLong();
		heldUntil.put(key, tick + HOLD_TICKS);
		heldStatus.put(key, why);
		return why;
	}

	// ---- bookkeeping --------------------------------------------------------------------------------------------

	private BlockPos at(int i) {
		return cursor.set(work.getLong(i));
	}

	private Status status(int i) {
		return STATUSES[workStatus.getByte(i)];
	}

	private void set(int i, Status s) {
		workStatus.set(i, (byte) s.ordinal());
	}

	/** A sort key (up to 43 bits, smaller first) and a position's index (up to a million) in one long. */
	private static long key(long order, int index) {
		return order << 20 | index;
	}

	private static int index(long key) {
		return (int) (key & 0xFFFFF);
	}

	/** Orders a kind's positions by their keys: a sort of plain numbers, no comparisons through objects. */
	private static void sort(LongArrayList list) {
		if (list.size() > 1) Arrays.sort(list.elements(), 0, list.size());
	}

	/** The block at {@code pos}, or null if its chunk isn't loaded; reads from the same chunk as last time when it can. */
	private @Nullable BlockState blockAt(BlockPos pos) {
		int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
		if (chunk == null || cx != chunkX || cz != chunkZ) {
			chunk = mc.level.getChunkSource().getChunk(cx, cz, false);
			chunkX = cx;
			chunkZ = cz;
			if (chunk == null) return null;
		}
		return chunk.getBlockState(pos);
	}

	@Override
	public String toString() {
		return "Build for " + owner;
	}
}
