package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.MovementPacketsEvent;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.service.Breaking;
import dev.myriadclient.api.service.PacketLimits;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.util.BlockInfo;
import dev.myriadclient.api.util.Mining;
import dev.myriadclient.api.util.Packets;
import dev.myriadclient.api.util.Reach;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Breaks blocks with its own action packets, mirroring how the server times a break:
 *
 * <ul>
 *   <li>A start that would finish the block at once (the held tool's progress per tick is 1 or more) breaks it on the
 *   spot. Instant breaks are batched by tool, so a whole tick's worth needs one swap.</li>
 *   <li>Otherwise the server remembers when the break started and, on the finish packet, accepts it once
 *   {@code progress per tick * (ticks since start + 1)} reaches 0.7, using the tool held at that moment.</li>
 *   <li>Efficiency counts through an attribute that follows the item held at the server's last tick, so a tool swapped
 *   in for just the packet mines at its base speed. Breaks are timed with the Efficiency the server has had, and in
 *   packet modes the tool is held at the server for the last stretch, so its Efficiency counts by the finish.</li>
 *   <li>Grim (2b2t) is stricter: it expects vanilla's full time, {@code ceil(1 / progress per tick)} ticks from the
 *   start, judged by the tool held at the start (or a better one seen on later swings), and about 300 ms between
 *   finishing one block and starting the next. Early finishes and starts build up allowances of about a second; past
 *   them, the packet is refused and the block comes back. Its timing is followed here ({@link GrimBreakTiming}), so
 *   packet modes finish at 0.7 and start the next block at once while those allowances last, and otherwise wait;
 *   only {@link Mode#FAST} ignores them.</li>
 *   <li>A finish sent too early isn't wasted on the server: it keeps mining that block by itself until it reaches 1.
 *   Fast mode's double break uses that to start a second block while the first finishes server-side.</li>
 *   <li>The server keeps the start of the last block it was told to start until another start arrives, so a finish
 *   alone breaks whatever appears there again (rebreak), once enough time has passed for that block.</li>
 * </ul>
 *
 * Everything happens at the start of a tick, before the movement packet, where vanilla sends break packets; with
 * {@code rotate} the server has been told where you look in the tick before. Results come from the server's acknowledgements (see
 * {@link dev.myriadclient.impl.network.BlockAckTracker}).
 */
public final class BreakManager implements Breaking {
	static final float PACKET_THRESHOLD = 0.7f;
	/**
	 * Ticks between finishing one block and starting another: vanilla's own gap (its 5-tick delay, then the next tick),
	 * which Grim checks for (about 300 ms).
	 */
	private static final int FINISH_GAP = 6;
	/** {@link Mode#FAST_GRIM}: how far above the block the decoy starts go (out of reach, so the server ignores them). */
	private static final int DECOY_HEIGHT = 955;
	/** {@link Mode#FAST_GRIM}: at most this many decoys a second (each is a flag on builds that check for them). */
	private static final int DECOYS_PER_SECOND = 40;
	/** How long to wait for the server's word on a finished break, beyond when it's due. */
	private static final int GRACE_TICKS = 40;
	/** Breaker ids for the crack overlay, apart from real entities (and the player's own mining). */
	private static final int CRACK_PRIMARY = Integer.MIN_VALUE + 17, CRACK_DELAYED = Integer.MIN_VALUE + 18;

	private enum Phase {
		/** Waiting its turn. */
		QUEUED,
		/** The server is mining it as its current break. */
		MINING,
		/** Finished early; the server finishes it by itself (double break). */
		DELAYED,
		/** The finishing packet is out; waiting for the server. */
		AWAITING
	}

	private final class Job {
		final Object owner;
		final BlockPos pos;
		final Options options;
		final Block block;
		final long order;
		final CompletableFuture<Boolean> result = new CompletableFuture<>();
		final Attempt attempt;
		int priority;
		Phase phase = Phase.QUEUED;
		Direction face = Direction.UP;
		Vec3 aim;
		int started, due, deadline, lastSent;
		/** When the start went out, in wall-clock ms: Grim times breaks in real time. */
		long startedMs;
		float progress;
		boolean acked, finishLater;

		Job(Object owner, BlockPos pos, int priority, Options options, Block block) {
			this.owner = owner;
			this.pos = pos;
			this.priority = priority;
			this.options = options;
			this.block = block;
			this.order = counter++;
			this.attempt = new Attempt(pos, Check.OK, result);
		}

		/** Mined with its own packets (hand free), rather than as if holding the attack button. */
		boolean packet() {
			return options.mode() != Mode.VANILLA;
		}

		/** Finished at 70% (servers that don't check break times, or with Grim's decoy starts). */
		boolean fast() {
			return options.mode() == Mode.FAST || grim();
		}

		/** Fast, with decoy starts that hide the speed from Grim. */
		boolean grim() {
			return options.mode() == Mode.FAST_GRIM;
		}

		/** Times its finishes and starts by Grim's own allowances (see {@link GrimBreakTiming}). */
		boolean grimTimed() {
			return options.mode() == Mode.PACKET || options.mode() == Mode.FAST_GRIM;
		}

		/** Waits vanilla's gap after a finish before any start: holding the attack button does. */
		boolean keepsGap() {
			return options.mode() == Mode.VANILLA;
		}
	}

	private final Minecraft mc = Minecraft.getInstance();
	private final List<Job> jobs = new ArrayList<>();
	private Job primary, delayed;
	/** A waiting break whose better tool is being brought in from the inventory: it doesn't start until it arrives. */
	private Job toolComing, toolWaitJob;
	private int toolWait;
	/** Most ticks a break waits for its tool to come in before starting with what's in the hotbar. */
	private static final int TOOL_WAIT_TICKS = 10;
	/** The server's remembered break: where it was last told to start, and when (client ticks). */
	private BlockPos rebreakPos;
	private int rebreakStart;
	private boolean rebreakReady, holding;
	/** Grim's view of break timing, followed from every dig packet sent. */
	private final GrimBreakTiming grim = new GrimBreakTiming();
	/** Efficiency of the item the server held, sampled each tick: {ms, level}, oldest first. */
	private final ArrayDeque<long[]> heldEfficiency = new ArrayDeque<>();
	/** When decoys went out (ms), for their budget; and the tick the last one did (a finish waits for the next). */
	private final ArrayDeque<Long> decoyTimes = new ArrayDeque<>();
	private int decoyTick = Integer.MIN_VALUE;
	/**
	 * The block the server is finishing by itself, if any. It has room for one: while it's taken, an early finish is
	 * ignored, so double break waits for it to clear (the block there changes) before using it again.
	 */
	private BlockPos serverDelayedPos;
	private Block serverDelayedBlock;
	private long counter;
	private int tick, finishCooldown;
	/** The tick a swing last went out, from anyone (vanilla's for your click, a module's): one a tick is all Grim wants. */
	private int swungTick = -1;
	/** A tool was swapped in this step: it goes back at the end of the step. */
	private boolean swapBackNow;
	/**
	 * When the last finish went out, in wall-clock ms. Grim measures breaks and the pause between them in real time, so
	 * tick counts alone aren't enough: a client that falls behind runs several ticks back to back.
	 */
	private long lastStopMs;

	// ---- API ----------------------------------------------------------------------------------------------------

	@Override
	public Check check(BlockPos pos, Options o) {
		if (mc.player == null || mc.level == null) return Check.UNAVAILABLE;
		BlockState state = mc.level.getBlockState(pos);
		if (nothingThere(state)) return Check.NOTHING_THERE;
		if (BlockInfo.isUnbreakable(state) || Mining.delta(state, pos, toolSlot(o, state, pos)) <= 0) return Check.UNBREAKABLE;
		if (!inRange(pos, o)) return Check.OUT_OF_RANGE;
		if (Myriad.placement().isPending(pos)) return Check.PENDING;
		return Check.OK;
	}

	@Override
	public Attempt breakBlock(Object owner, BlockPos pos, int priority, Options options) {
		Job existing = find(pos);
		if (existing != null) {
			if (priority > existing.priority) {
				existing.priority = priority;
				sort();
			}
			return existing.attempt;
		}
		Check check = check(pos, options);
		if (!check.ok()) return Attempt.refused(pos, check);
		Job job = new Job(owner, pos.immutable(), priority, options, mc.level.getBlockState(pos).getBlock());
		jobs.add(job);
		sort();
		return job.attempt;
	}

	@Override
	public void cancel(Object owner) {
		for (Job j : new ArrayList<>(jobs)) if (j.owner == owner) finish(j, false);
	}

	@Override
	public void cancel(BlockPos pos) {
		Job j = find(pos);
		if (j != null) finish(j, false);
	}

	@Override
	public boolean isPending(BlockPos pos) {
		return find(pos) != null;
	}

	@Override
	public float progress(BlockPos pos) {
		Job j = find(pos);
		if (j == null) return 0;
		return switch (j.phase) {
			case QUEUED -> 0;
			case MINING -> Math.min(1, j.progress);
			case DELAYED, AWAITING -> 1;
		};
	}

	@Override
	public boolean canRebreak(BlockPos pos) {
		return rebreakable(pos, false);
	}

	/**
	 * Whether a finish alone breaks {@code pos} again. The server measures from the start it remembers, with the block
	 * there now: it needs 70% of that block's time to have passed. Grim measures from the last finish (see
	 * {@link GrimBreakTiming}), unless {@code fast} (servers that don't check).
	 */
	private boolean rebreakable(BlockPos pos, boolean fast) {
		if (!rebreakReady || !pos.equals(rebreakPos) || mc.player == null || mc.level == null) return false;
		BlockState state = mc.level.getBlockState(pos);
		if (nothingThere(state)) return false;
		float rate = rate(state, pos, toolSlot(Options.PACKET, state, pos));
		if (rate <= 0 || rate * ((tick - rebreakStart) * tpsFactor() + 1) < PACKET_THRESHOLD) return false;
		return fast || grim.finishOk(System.currentTimeMillis());
	}

	// ---- ticking ------------------------------------------------------------------------------------------------

	/** Faces whatever is about to need it: the block being mined, or the first one waiting. */
	@Subscribe
	private void onTickStart(TickEvent.Pre e) {
		if (jobs.isEmpty() || mc.player == null) return;
		Job focus = primary != null && primary.options.rotate() ? primary : null;
		if (focus == null) {
			for (Job j : jobs) {
				if (j.phase == Phase.QUEUED && j.options.rotate()) {
					focus = j;
					break;
				}
			}
		}
		if (focus == null) return;
		aim(focus);
		if (focus.aim == null) return;
		float[] r = PlacementManager.anglesFromNextPosition(focus.aim);
		Myriad.rotations().request(this, r[0], r[1], Rotations.PRIORITY_HIGH, PlacementManager.MOVE_FIX, null);
	}

	/** Aims again from where you are now, just before the rotation goes out (see PlacementManager). */
	@Subscribe(priority = Priority.HIGH)
	private void onBeforeRotationSent(MovementPacketsEvent e) {
		Job focus = primary != null && primary.options.rotate() ? primary : null;
		if (focus == null) for (Job j : jobs) if (j.phase == Phase.QUEUED && j.options.rotate()) {
			focus = j;
			break;
		}
		if (focus == null || focus.aim == null || mc.player == null) return;
		float[] r = dev.myriadclient.api.util.MathUtil.anglesTo(mc.player.getEyePosition(), focus.aim);
		Myriad.rotations().request(this, r[0], r[1], PlacementManager.REFINED_PRIORITY, PlacementManager.MOVE_FIX, null);
	}

	/**
	 * Breaks at the start of the tick, before this tick's movement: the server already has the rotation sent last tick,
	 * and it's where vanilla sends break packets (Grim flags them after a movement packet).
	 */
	@Subscribe(priority = RotationManager.ACT_PRIORITY - 20)
	private void onBreakTime(TickEvent.Pre e) {
		step();
	}

	@Subscribe
	private void onTickEnd(TickEvent.Post e) {
		tick++;
		if (finishCooldown > 0) finishCooldown--;
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		for (Job j : new ArrayList<>(jobs)) j.result.complete(false);
		jobs.clear();
		primary = delayed = null;
		rebreakReady = holding = false;
		rebreakPos = serverDelayedPos = null;
		grim.reset();
		heldEfficiency.clear();
		decoyTimes.clear();
	}

	/**
	 * Follows Grim's timing from every dig packet that goes out, ours or anyone's (vanilla mining included): starts and
	 * finishes, and the samples it takes on movement and swing packets. Lowest priority: what's really sent.
	 */
	@Subscribe(priority = Priority.LOWEST, packets = {ServerboundPlayerActionPacket.class, ServerboundMovePlayerPacket.class, ServerboundSwingPacket.class})
	private void onSend(PacketEvent.Send e) {
		if (e.isCancelled() || mc.player == null || mc.level == null) return;
		long now = System.currentTimeMillis();
		switch (e.packet()) {
			case ServerboundPlayerActionPacket p when p.getAction() == Action.START_DESTROY_BLOCK -> grim.started(p.getPos(), grimDamage(p.getPos()), now);
			case ServerboundPlayerActionPacket p when p.getAction() == Action.STOP_DESTROY_BLOCK -> grim.finished(now);
			case ServerboundMovePlayerPacket m -> sampleGrim();
			case ServerboundSwingPacket s -> {
				swungTick = tick;
				sampleGrim();
			}
			default -> {
			}
		}
	}

	private void sampleGrim() {
		BlockPos target = grim.target();
		if (target != null) grim.sampled(grimDamage(target));
	}

	/** The progress per tick Grim credits for {@code pos} now: with what the server holds, at the Efficiency it has had. */
	private double grimDamage(BlockPos pos) {
		BlockState state = mc.level.getBlockState(pos);
		if (state.isAir()) return Double.POSITIVE_INFINITY;
		return rate(state, pos, Myriad.inventory().serverSlot());
	}

	/**
	 * Progress per tick with {@code slot} held, as the server and Grim credit it: Efficiency counts only as far as
	 * the items the server held lately had it (see {@link #creditedEfficiency(int)}).
	 */
	private float rate(BlockState state, BlockPos pos, int slot) {
		return Mining.delta(state, pos, slot, creditedEfficiency(slot));
	}

	/**
	 * The Efficiency the server and Grim count for {@code slot}: its own, but no more than any item the server held
	 * in about the time the attribute takes to reach Grim (it follows the item held at a server tick, and Grim gets it
	 * a round trip later): one and a half pings plus a margin. A tool swapped in just now mines at its base speed there.
	 */
	private int creditedEfficiency(int slot) {
		int level = slot >= 0 ? Mining.efficiency(mc.player.getInventory().getItem(slot)) : 0;
		if (level == 0) return 0;
		level = Math.min(level, Mining.efficiency(mc.player.getInventory().getItem(Myriad.inventory().serverSlot())));
		long since = System.currentTimeMillis() - efficiencyWindowMs();
		for (long[] sample : heldEfficiency) if (sample[0] >= since) level = Math.min(level, (int) sample[1]);
		return level;
	}

	private boolean hasEfficiency(int slot) {
		return slot >= 0 && Mining.efficiency(mc.player.getInventory().getItem(slot)) > 0;
	}

	private static long efficiencyWindowMs() {
		return Myriad.server().ping() * 3L / 2 + 200;
	}

	/** Notes the Efficiency of what the server holds this tick, keeping a few seconds of it. */
	private void sampleEfficiency() {
		long now = System.currentTimeMillis();
		heldEfficiency.addLast(new long[]{now, Mining.efficiency(mc.player.getInventory().getItem(Myriad.inventory().serverSlot()))});
		while (heldEfficiency.size() > 1 && now - heldEfficiency.peekFirst()[0] > 5000) heldEfficiency.pollFirst();
	}

	private void step() {
		if (mc.player == null || mc.level == null || mc.gameMode == null) return;
		sampleEfficiency();
		if (jobs.isEmpty() && !holding) return;
		// Another module's swap still to go back is its own business: only ours go back early.
		boolean othersPending = Myriad.inventory() instanceof InventoryManager inv && inv.restorePending();
		swapBackNow = false;
		if (serverDelayedPos != null && !mc.level.getBlockState(serverDelayedPos).is(serverDelayedBlock)) serverDelayedPos = null;
		validate();
		fetchTool();
		rebreak();
		instants();
		mine();
		updateHold();
		if (swapBackNow && !othersPending && Myriad.inventory() instanceof InventoryManager inv) inv.restoreNow();
	}

	/** Drops finished and impossible jobs, and completes the ones the server has dealt with. */
	private void validate() {
		for (Job j : new ArrayList<>(jobs)) {
			if (j.result.isDone()) {
				finish(j, j.result.getNow(false));
				continue;
			}
			BlockState state = mc.level.getBlockState(j.pos);
			boolean gone = !state.is(j.block);
			switch (j.phase) {
				case QUEUED, MINING -> {
					if (gone) finish(j, true);
					else if (!inRange(j.pos, j.options)) finish(j, false);
				}
				case DELAYED -> {
					if (gone) finish(j, true);
					else if (tick > j.deadline) finish(j, false);
				}
				case AWAITING -> {
					if (!j.acked) continue;
					if (gone) finish(j, true);
					else if (tick > j.deadline) finish(j, false);
					else if (j.fast() && j.pos.equals(rebreakPos) && rebreakReady && !j.pos.equals(serverDelayedPos) && tick - j.lastSent >= 2 && budget(1) && grimLets(j)) {
						// The finish arrived early while the server was already finishing another block by itself, so
						// it was ignored; the server still remembers this start, so finishing again soon works.
						j.lastSent = tick;
						withTool(toolSlot(j.options, state, j.pos), () -> Packets.sendSequenced(j.pos,
							seq -> new ServerboundPlayerActionPacket(Action.STOP_DESTROY_BLOCK, j.pos, j.face, seq)));
						swing(j);
					}
				}
			}
		}
	}

	/** Packet mode: a block back where the server's remembered break was is broken instantly. */
	private void rebreak() {
		if (!rebreakReady) return;
		Job j = find(rebreakPos);
		if (j == null || j.phase != Phase.QUEUED || !j.packet() || !canAct(j) || !budget(1)) return;
		// Fast Grim: past Grim's allowance, a decoy first and the finish next tick.
		if (!rebreakable(j.pos, j.options.mode() == Mode.FAST) && !(j.grim() && rebreakable(j.pos, true) && grimLets(j))) return;
		BlockState state = mc.level.getBlockState(j.pos);
		withTool(toolSlot(j.options, state, j.pos), () -> finishWith(j, Action.STOP_DESTROY_BLOCK, true, false));
	}

	/** Breaks everything waiting that the right tool breaks in one hit, grouped by tool to save swaps. */
	private void instants() {
		List<Job> instant = new ArrayList<>();
		List<Integer> slots = new ArrayList<>();
		boolean vanillaUsed = false;
		for (Job j : jobs) {
			if (j.phase != Phase.QUEUED) continue;
			BlockState state = mc.level.getBlockState(j.pos);
			int slot = toolSlot(j.options, state, j.pos);
			if (rate(state, j.pos, slot) < 1) continue;
			// Vanilla can't break more than one block a tick.
			if (!j.packet() && vanillaUsed) continue;
			// Starting anything straight after a finish is too fast for vanilla, and for Grim.
			if (!startAllowed(j)) continue;
			// Any start restarts the server's clock for the block being mined, so only more urgent blocks cut in.
			if (primary != null && primary.phase == Phase.MINING && j.priority <= primary.priority) continue;
			if (!canAct(j) || !budget(instant.size() + 1)) continue;
			if (!j.packet()) vanillaUsed = true;
			instant.add(j);
			slots.add(slot);
		}
		while (!instant.isEmpty()) {
			int slot = slots.getFirst();
			List<Job> group = new ArrayList<>();
			for (int i = instant.size() - 1; i >= 0; i--) {
				if (slots.get(i) != slot) continue;
				group.addFirst(instant.remove(i));
				slots.remove(i);
			}
			withTool(slot, () -> {
				for (Job j : group) finishWith(j, Action.START_DESTROY_BLOCK, true, false);
			});
			// An instant start also resets when the server thinks the current break started: start counting again.
			rebreakReady = false;
			rebreakStart = tick;
			if (primary != null && primary.phase == Phase.MINING) {
				primary.started = tick;
				primary.startedMs = System.currentTimeMillis();
				primary.progress = 0;
			}
		}
	}

	/** The block being mined: progress, finishing, double break, and starting the next one. */
	private void mine() {
		if (primary != null && primary.phase == Phase.MINING) {
			Job p = primary;
			BlockState state = mc.level.getBlockState(p.pos);
			int slot = toolSlot(p.options, state, p.pos);
			// What the server credits the tool with at the finish: Efficiency only once it has held it a while.
			float rate = rate(state, p.pos, slot);
			boolean done;
			if (p.packet()) {
				// The server takes a finish once 70% of the time has passed (ticks it has run since the start). Plain
				// fast mode stops there; the others also wait for Grim's timing to allow it (finishing early only
				// while its allowance lasts), and Fast Grim spends a decoy start when it doesn't.
				float elapsed = (tick - p.started) * tpsFactor();
				p.progress = rate * (elapsed + 1) / PACKET_THRESHOLD;
				done = rate * (elapsed + 1) >= PACKET_THRESHOLD && (p.options.mode() == Mode.FAST || budget(1) && grimLets(p));
			} else {
				p.progress += rate;
				done = p.progress >= 1;
			}
			mc.level.destroyBlockProgress(CRACK_PRIMARY, p.pos, Math.min(9, (int) (p.progress * 10)));
			// Holding the attack button swings every tick; packet mining only swings to start and finish.
			if (p.options.swing() && !p.packet()) mc.player.swing(InteractionHand.MAIN_HAND);
			if (done && budget(1) && canAct(p)) {
				withTool(slot, () -> finishWith(p, Action.STOP_DESTROY_BLOCK, true, p.packet()));
				if (p.packet()) rebreakReady = true;
			} else if (!done && p.fast() && p.options.doubleBreak() && serverDelayedPos == null) {
				Job next = nextToStart(true);
				// Grim: the early finish has to pass its timing (a decoy first if not), and the next start, straight
				// after it, its start-too-soon allowance.
				if (next != null && p.grim() && (!grim.startOkRightAfterFinish() || !grimLets(p))) next = null;
				if (next != null && budget(2)) {
					// Finish early: the server keeps mining this one by itself, and the next one starts now.
					primary = null;
					mc.level.destroyBlockProgress(CRACK_PRIMARY, p.pos, -1);
					becomeDelayed(p, rate);
					Packets.sendSequenced(p.pos, seq -> new ServerboundPlayerActionPacket(Action.STOP_DESTROY_BLOCK, p.pos, p.face, seq));
					swing(p);
				}
			}
		}
		if (delayed != null) {
			int stage = (int) Math.min(9, 10f * (tick - delayed.started) / Math.max(1, delayed.due - delayed.started));
			mc.level.destroyBlockProgress(CRACK_DELAYED, delayed.pos, stage);
		}
		if (primary == null && !mc.gameMode.isDestroying()) {
			Job next = nextToStart(false);
			if (next != null && startAllowed(next) && budget(1)) start(next);
		}
	}

	/**
	 * Whether {@code j} may start now, after the last finish: vanilla mode waits vanilla's own gap, Grim-timed modes as
	 * long as Grim's start-too-soon allowance lasts, and plain fast mode doesn't wait.
	 */
	private boolean startAllowed(Job j) {
		if (j.keepsGap()) return gapOver();
		return !j.grimTimed() || grim.startOk(System.currentTimeMillis());
	}

	/**
	 * Whether a finish for {@code j} may go out now as far as Grim's timing goes (always, outside Grim-timed modes).
	 * Past its allowance, Fast Grim sends a decoy start, when one may go, so the finish passes next tick; packet mode
	 * waits for the allowance to come back. Nothing finishes in the tick a decoy went out (one break packet a tick, as
	 * Grim's MultiBreak expects).
	 */
	private boolean grimLets(Job j) {
		if (!j.grimTimed()) return true;
		if (decoyTick == tick) return false;
		long now = System.currentTimeMillis();
		if (grim.finishOk(now)) return true;
		if (j.grim() && grim.startOk(now) && decoyBudget() && budget(1)) decoyStart(j);
		return false;
	}

	/** The first waiting block that takes more than one hit and can be started now; double break wants packet mode. */
	private Job nextToStart(boolean forDoubleBreak) {
		for (Job j : jobs) {
			if (j.phase != Phase.QUEUED) continue;
			if (forDoubleBreak && (!j.fast() || !j.options.doubleBreak())) continue;
			BlockState state = mc.level.getBlockState(j.pos);
			float rate = rate(state, j.pos, toolSlot(j.options, state, j.pos));
			if (rate >= 1 || rate <= 0) continue;
			if (j != toolComing && canAct(j)) return j;
		}
		return null;
	}

	private void start(Job j) {
		aim(j);
		BlockState state = mc.level.getBlockState(j.pos);
		int slot = toolSlot(j.options, state, j.pos);
		// Start with the tool in hand: Grim times the break from what you hold as it starts (and the best it sees after).
		withTool(slot, () -> {
			Packets.sendSequenced(j.pos, seq -> new ServerboundPlayerActionPacket(Action.START_DESTROY_BLOCK, j.pos, j.face, seq));
			swing(j);
		});
		j.phase = Phase.MINING;
		j.started = tick;
		j.startedMs = System.currentTimeMillis();
		j.progress = 0;
		primary = j;
		rebreakPos = j.pos;
		rebreakStart = tick;
		rebreakReady = false;
	}

	/**
	 * A start for the spot {@link #DECOY_HEIGHT} blocks above {@code j}'s block, out of reach: the server ignores it,
	 * while Grim (on servers with ViaVersion) times air from then on, which breaks at once, so the next finish passes
	 * whatever block it's for. Face down, as you'd see a block that far above.
	 */
	private void decoyStart(Job j) {
		BlockPos decoy = j.pos.above(DECOY_HEIGHT);
		decoyTick = tick;
		decoyTimes.addLast(System.currentTimeMillis());
		Packets.sendSequenced(seq -> new ServerboundPlayerActionPacket(Action.START_DESTROY_BLOCK, decoy, Direction.DOWN, seq));
		swing(j);
	}

	/** Whether another decoy fits in {@link #DECOYS_PER_SECOND}. */
	private boolean decoyBudget() {
		long now = System.currentTimeMillis();
		while (!decoyTimes.isEmpty() && now - decoyTimes.peekFirst() > 1000) decoyTimes.pollFirst();
		return decoyTimes.size() < DECOYS_PER_SECOND;
	}

	/** {@code j} was finished early: the server now finishes it by itself once its progress reaches 1. */
	private void becomeDelayed(Job j, float rate) {
		j.due = j.started + (int) Math.ceil(serverTicksToReach(rate, 1f) / tpsFactor());
		j.deadline = j.due + GRACE_TICKS;
		j.phase = Phase.DELAYED;
		delayed = j;
		serverDelayedPos = j.pos;
		serverDelayedBlock = j.block;
	}

	/**
	 * Sends the packet that should break {@code j}, predicting the break client-side if {@code predict}, and settles
	 * the job from the server's acknowledgement. With {@code finishLater}, a refusal may still turn into a break the
	 * server finishes by itself, so the job waits for that a while.
	 */
	private void finishWith(Job j, Action action, boolean predict, boolean finishLater) {
		aim(j);
		if (j == primary) {
			primary = null;
			mc.level.destroyBlockProgress(CRACK_PRIMARY, j.pos, -1);
		}
		j.phase = Phase.AWAITING;
		j.finishLater = finishLater;
		j.deadline = Integer.MAX_VALUE;
		j.lastSent = tick;
		if (action == Action.STOP_DESTROY_BLOCK) {
			lastStopMs = System.currentTimeMillis();
			if (j.keepsGap()) finishCooldown = FINISH_GAP;
		}
		// Waiting for the server: the block stays on screen until its update says it's gone.
		boolean show = predict && !(j.packet() && j.options.waitForServer());
		CompletableFuture<BlockState> ack = Packets.sendSequenced(j.pos, seq -> {
			if (show) mc.gameMode.destroyBlock(j.pos);
			return new ServerboundPlayerActionPacket(action, j.pos, j.face, seq);
		});
		swing(j);
		ack.whenComplete((state, t) -> {
			if (j.result.isDone()) return;
			if (t != null) {
				finish(j, false);
			} else if (!state.is(j.block)) {
				finish(j, true);
			} else if (finishLater && serverDelayedPos == null && delayed == null) {
				// Finished a little early (the server lagged): it keeps mining this one by itself.
				BlockState now = mc.level.getBlockState(j.pos);
				becomeDelayed(j, rate(now, j.pos, toolSlot(j.options, now, j.pos)));
			} else if (finishLater) {
				// Early while the server was already finishing another: ignored, so it's finished again shortly.
				j.acked = true;
				j.deadline = tick + GRACE_TICKS;
			} else {
				finish(j, false);
			}
		});
	}

	private void finish(Job j, boolean broken) {
		if (j == primary) {
			if (j.phase == Phase.MINING && mc.getConnection() != null) {
				// Face DOWN, as vanilla always sends it (Grim's PositionBreakB flags any other).
				mc.getConnection().send(new ServerboundPlayerActionPacket(Action.ABORT_DESTROY_BLOCK, j.pos, Direction.DOWN));
			}
			if (mc.level != null) mc.level.destroyBlockProgress(CRACK_PRIMARY, j.pos, -1);
			primary = null;
		}
		if (j == delayed) {
			if (mc.level != null) mc.level.destroyBlockProgress(CRACK_DELAYED, j.pos, -1);
			delayed = null;
		}
		jobs.remove(j);
		j.result.complete(broken);
	}

	/**
	 * Holds the tool a break needs at the server between packets: the vanilla-mode block being mined, the
	 * double-broken block around when the server finishes it (it checks the held tool every tick), and a packet-mode
	 * block for the last stretch before it's done, so the tool's Efficiency counts by the finish (it only does once
	 * the server has ticked with the tool held, and Grim has had it back). Not while you use an item in your main hand.
	 */
	private void updateHold() {
		int slot = -1;
		int lead = (int) Math.ceil(efficiencyWindowMs() / 50.0) + 2;
		if (delayed != null && tick >= delayed.due - lead) {
			BlockState state = mc.level.getBlockState(delayed.pos);
			slot = toolSlot(delayed.options, state, delayed.pos);
		} else if (primary != null && primary.phase == Phase.MINING) {
			Job p = primary;
			BlockState state = mc.level.getBlockState(p.pos);
			int tool = toolSlot(p.options, state, p.pos);
			// With the tool's Efficiency, the block would be done within the time it takes to count: hold it from now.
			float full = Mining.delta(state, p.pos, tool);
			if (!p.packet() || hasEfficiency(tool) && full * (tick - p.started + 1 + lead) >= PACKET_THRESHOLD) slot = tool;
		} else if (primary == null) {
			// Between blocks: keep (or bring) the next block's tool, if its Efficiency would make a difference, so it
			// counts by the time that block starts.
			for (Job j : jobs) {
				if (j.phase != Phase.QUEUED || !j.packet()) continue;
				BlockState state = mc.level.getBlockState(j.pos);
				int tool = toolSlot(j.options, state, j.pos);
				if (hasEfficiency(tool)) slot = tool;
				break;
			}
		}
		if (mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND) slot = -1;
		if (slot >= 0 && slot != mc.player.getInventory().getSelectedSlot()) {
			holding = Myriad.inventory().hold(this, slot, 3);
		} else if (holding) {
			Myriad.inventory().release(this);
			holding = false;
		}
	}

	// ---- helpers ------------------------------------------------------------------------------------------------

	/**
	 * Server ticks from the start until {@code progress per tick * (ticks + 1)} reaches {@code threshold}: how the
	 * server measures a break.
	 */
	static int serverTicksToReach(float ratePerTick, float threshold) {
		if (ratePerTick <= 0) return Integer.MAX_VALUE;
		return Math.max(0, (int) Math.ceil(threshold / ratePerTick - 1e-4) - 1);
	}

	/** Server ticks per client tick: under 1 while the server lags, so timed finishes wait for it. */
	private static float tpsFactor() {
		return Math.clamp(Myriad.server().tps() / 20f, 0.1f, 1f);
	}

	private void aim(Job j) {
		BlockHitResult hit = Reach.hitFor(j.pos, false, j.options.range());
		if (hit != null) {
			j.face = hit.getDirection();
			j.aim = hit.getLocation();
		} else {
			j.aim = Vec3.atCenterOf(j.pos);
		}
	}

	/** With rotate, only once the rotation just sent looks at the block; the face it lands on is the one used. */
	private boolean canAct(Job j) {
		if (!j.options.rotate()) return true;
		BlockHitResult look = Reach.rayHit(Myriad.rotations().serverYaw(), Myriad.rotations().serverPitch(), j.pos, j.options.range());
		if (look == null) return false;
		j.face = look.getDirection();
		return true;
	}

	/**
	 * Swings with a break packet, as vanilla does (Grim's NoSwingBreak, experimental, flags one without): shown if the
	 * options want the hand to swing, otherwise only sent, unless a swing already went out this tick.
	 */
	private void swing(Job j) {
		if (j.options.swing()) mc.player.swing(InteractionHand.MAIN_HAND);
		else if (swungTick != tick && mc.getConnection() != null) mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
	}

	/**
	 * Auto tool from the inventory: when the best tool for the block being mined, or the next one, is outside the
	 * hotbar, it's borrowed in (see {@link dev.myriadclient.api.service.Inventory#borrow}, which waits while you move, since
	 * Grim cancels clicks then, and puts it back once no break needs it); until then the best hotbar tool does. A
	 * packet-mode break already started is timed by the tool it started with, so then it's fetched for the next block
	 * instead.
	 */
	private void fetchTool() {
		// Borrowed tools stay while any break still needs them.
		for (Job q : jobs) {
			if (!q.options.autoTool()) continue;
			BlockState state = mc.level.getBlockState(q.pos);
			Myriad.inventory().borrow(this, toolSlot(q.options, state, q.pos), null);
		}
		Job j = primary != null && primary.phase == Phase.MINING && (!primary.packet() || primary.fast()) ? primary : null;
		if (j == null) for (Job q : jobs) if (q.phase == Phase.QUEUED) {
			j = q;
			break;
		}
		toolComing = null;
		if (j == null || !j.options.autoTool()) return;
		BlockState state = mc.level.getBlockState(j.pos);
		int from = Mining.fastestSlot(state, 9, 36);
		if (from < 0 || Mining.delta(state, j.pos, from) <= Mining.delta(state, j.pos, toolSlot(j.options, state, j.pos))) return;
		// A tool that's worse for this block makes room for it. While you move it takes a tick longer (your keys are
		// released first); a break that hasn't started waits for it rather than start with a worse tool, as packet
		// breaks are timed by the tool they start with.
		if (Myriad.inventory().borrow(this, from, s -> s.getDestroySpeed(state) > 1) < 0 && j.phase == Phase.QUEUED) {
			toolWait = j == toolWaitJob ? toolWait + 1 : 0;
			toolWaitJob = j;
			if (toolWait < TOOL_WAIT_TICKS) toolComing = j;
		}
	}

	private int toolSlot(Options o, BlockState state, BlockPos pos) {
		int selected = mc.player.getInventory().getSelectedSlot();
		if (!o.autoTool()) return selected;
		int best = Mining.fastestSlot(state, 0, 9);
		return best < 0 || Mining.delta(state, pos, selected) >= Mining.delta(state, pos, best) ? selected : best;
	}

	/**
	 * Runs {@code action} (break packets) with {@code slot} held at the server, swapping only if it isn't already. The
	 * swap goes back at the end of this step, in the same tick: Grim's PacketOrderE flags a slot change after an attack
	 * or a use in the same tick, not after a dig.
	 */
	private void withTool(int slot, Runnable action) {
		if (Myriad.inventory().serverSlot() == slot) {
			action.run();
			return;
		}
		Myriad.inventory().silentSwap(slot, action);
		swapBackNow = true;
	}

	/** Vanilla's pause after a finish is over, in ticks and in real time. */
	private boolean gapOver() {
		return finishCooldown == 0 && msSince(lastStopMs) >= FINISH_GAP * 50L;
	}

	private static long msSince(long ms) {
		return System.currentTimeMillis() - ms;
	}

	private static boolean budget(int packets) {
		return Myriad.limits().canSend(PacketLimits.Kind.BLOCK_ACTION, packets);
	}

	/** Measured to the nearest point of the block, as the server and Grim do. */
	private boolean inRange(BlockPos pos, Options o) {
		return Reach.canReach(pos, o.range());
	}

	private static boolean nothingThere(BlockState state) {
		return state.isAir() || state.getBlock() instanceof LiquidBlock;
	}

	private Job find(BlockPos pos) {
		if (pos == null) return null;
		for (Job j : jobs) if (j.pos.equals(pos)) return j;
		return null;
	}

	private void sort() {
		jobs.sort(Comparator.<Job>comparingInt(j -> -j.priority).thenComparingLong(j -> j.order));
	}
}
