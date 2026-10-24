package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.MovementPacketsEvent;
import dev.myriadclient.api.event.events.ScreenOpenEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.PacketLimits;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.util.BlockInfo;
import dev.myriadclient.api.util.MathUtil;
import dev.myriadclient.api.util.Reach;
import dev.myriadclient.api.util.Threads;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.network.ActionTiming;
import dev.myriadclient.impl.network.BlockAckTracker;
import dev.myriadclient.impl.util.Clock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SignItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
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
	/** How long after a module places a sign the editor the server opens for it is kept closed. */
	private static final long SIGN_EDITOR_MS = 2000;
	/**
	 * A placement, or with {@code use} a click that uses the block, waiting for its rotation. A solved {@code click}
	 * waits for the look it was solved with ({@code sendYaw}/{@code sendPitch}, a hair off at random) rather than one
	 * aimed at its point.
	 */
	private record Queued(Object owner, BlockPos pos, PlacementAim.Aim aim, int slot, Options options, CompletableFuture<Boolean> result, int expires, boolean use,
	                      @Nullable Click click, float sendYaw, float sendPitch) {
		BlockHitResult target() {
			return aim.hit();
		}
	}

	/** How close the look the server has must be to a solved click's, in degrees, for it to be made. */
	private static final float FACING_TOLERANCE = 0.05f;

	private final Minecraft mc = Minecraft.getInstance();
	private final BlockAckTracker acks;
	private final Map<BlockPos, CompletableFuture<Boolean>> pending = new HashMap<>();
	private final List<Queued> queue = new ArrayList<>();
	private final ClickSolver solver = new ClickSolver(this);
	private final PlacementAim aims = new PlacementAim();
	private final FollowLook follow = new FollowLook();
	private int tick;
	/** The yaw the server had at the start of the last tick (NaN: none yet). */
	private float yawTickAgo = Float.NaN;

	public PlacementManager(BlockAckTracker acks) {
		this.acks = acks;
	}

	/** What's subscribed beside it: the look after a solved click. */
	public List<Object> parts() {
		return List.of(follow);
	}

	@Override
	public boolean isPending(BlockPos pos) {
		return pending.containsKey(pos);
	}

	@Override
	public Solution solve(BlockPos pos, BlockState wanted, int hotbarSlot, Options o) {
		Threads.assertRender();
		if (hotbarSlot < 0 || hotbarSlot > 8 || mc.player == null) return Solution.none(Check.UNAVAILABLE);
		return solver.solve(pos.immutable(), wanted, placing(hotbarSlot), o);
	}

	/** Whether the look after a solved click is still to go out this tick: a break's turn now would take its place. */
	boolean followPending() {
		return follow.pending();
	}

	/** Whether a solved click may be aimed (looking at the block only in the packet after it): not while Grim would check its own look. */
	boolean aimedAllowed() {
		return follow.aimedAllowed();
	}

	@Override
	public Attempt place(Object owner, Click c, int hotbarSlot, Options o) {
		Threads.assertRender();
		BlockPos pos = c.pos();
		if (hotbarSlot < 0 || hotbarSlot > 8 || mc.player == null) return Attempt.refused(pos, Check.UNAVAILABLE);
		// The click carries its own look, sneak and air placement; range, swing and the rest stay the caller's.
		Options co = o.withRotate(false).withSneak(c.sneak()).withAirPlace(c.air() || o.airPlace());
		BlockPlaceContext context = placeContext(c.hit(), hotbarSlot);
		if (!context.getClickedPos().equals(pos)) return Attempt.refused(pos, Check.OCCUPIED);
		Check check = spaceCheck(pos, co, context);
		if (!check.ok()) return Attempt.refused(pos, check);
		if (!c.turns()) return submit(owner, pos, new PlacementAim.Aim(c.hit(), mc.player.getEyeHeight()), hotbarSlot, co);
		// One look at a time: it joins the clicks waiting if they want the same one, otherwise it's asked for again later
		// (queued behind a different look, it would only run out of time waiting).
		for (Queued q : queue) {
			if (q.click == null || !near(c.yaw(), c.pitch(), q.click.yaw(), q.click.pitch())) return Attempt.refused(pos, Check.RATE_LIMITED);
		}
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		pending.put(pos, result);
		result.whenComplete((placed, t) -> pending.remove(pos, result));
		ThreadLocalRandom r = ThreadLocalRandom.current();
		float yaw = c.yaw() + (float) r.nextDouble(-FollowLook.LOOK_JITTER, FollowLook.LOOK_JITTER);
		float pitch = Math.clamp(c.pitch() + (float) r.nextDouble(-FollowLook.LOOK_JITTER, FollowLook.LOOK_JITTER), -89.9f, 89.9f);
		queue.add(new Queued(owner, pos, new PlacementAim.Aim(c.hit(), mc.player.getEyeHeight()), hotbarSlot, co, result, tick + QUEUE_TICKS, false, c, yaw, pitch));
		return new Attempt(pos, Check.OK, result);
	}

	@Override
	public Check check(BlockPos pos, Options o) {
		Check space = spaceCheck(pos, o);
		if (!space.ok()) return space;
		return target(null, pos, o) != null ? Check.OK : refusal(pos, o);
	}

	/** Why there's no click: {@link #supportCheck}, or unavailable when a hook refused one core would make. */
	private Check refusal(BlockPos pos, Options o) {
		Check c = supportCheck(pos, o);
		return c.ok() ? Check.UNAVAILABLE : c;
	}

	/** Why no face qualified: nothing to click (and no air face you can see, for air placements), or only faces you can't see. */
	private Check supportCheck(BlockPos pos, Options o) {
		if (!aims.clickTargets(pos, o.sneak()).isEmpty()) return Check.NOT_VISIBLE;
		return o.airPlace() ? Check.NOT_VISIBLE : Check.NO_SUPPORT;
	}

	/** Everything but support: replaceable, in range, not pending, no entity in the way, budget left. */
	private Check spaceCheck(BlockPos pos, Options o) {
		return spaceCheck(pos, o, null);
	}

	/** With a click's {@code context}, the space counts as free where vanilla would place there (see {@link #placeContext}). */
	private Check spaceCheck(BlockPos pos, Options o, @Nullable BlockPlaceContext context) {
		if (mc.player == null || mc.level == null) return Check.UNAVAILABLE;
		if (isPending(pos) || Myriad.breaking().isPending(pos)) return Check.PENDING;
		BlockState state = mc.level.getBlockState(pos);
		if (context != null ? !context.canPlace() : !state.canBeReplaced()) return Check.OCCUPIED;
		if (Double.isNaN(aims.eyeHeightFor(new AABB(pos), o.range()))) return Check.OUT_OF_RANGE;
		boolean blocked = !mc.level.getEntities((Entity) null, new AABB(pos),
			e -> e.isAlive() && e.isPickable() || e == mc.player && e.getBoundingBox().intersects(new AABB(pos))).isEmpty();
		if (blocked) return Check.ENTITY_IN_WAY;
		if (!Myriad.limits().canSend(PacketLimits.Kind.INTERACT, 1)) return Check.RATE_LIMITED;
		return Check.OK;
	}

	/**
	 * The click for {@code pos}: on the best visible face you can reach, any reachable one unless visible faces are
	 * required, or the empty space itself for an air placement.
	 */
	private PlacementAim.Aim target(Object owner, BlockPos pos, Options o) {
		PlacementAim.Aim own = aims.target(pos, o);
		if (!AIM.hooked()) return own;
		BlockHitResult hit = AIM.call(owner, new Target(pos.immutable(), o), t -> own == null ? null : own.hit());
		if (hit == null) return null;
		if (own != null && hit == own.hit()) return own;
		return new PlacementAim.Aim(hit, mc.player.getEyeHeight());
	}

	@Override
	public Attempt place(Object owner, BlockPos pos, int hotbarSlot, Options o) {
		Threads.assertRender();
		if (hotbarSlot < 0 || hotbarSlot > 8) return Attempt.refused(pos, Check.UNAVAILABLE);
		Check space = spaceCheck(pos, o);
		if (!space.ok()) return Attempt.refused(pos, space);
		PlacementAim.Aim aim = target(owner, pos, o);
		if (aim == null) return Attempt.refused(pos, refusal(pos, o));
		return submit(owner, pos, aim, hotbarSlot, o);
	}

	@Override
	public Attempt place(Object owner, BlockHitResult hit, int hotbarSlot, Options o) {
		Threads.assertRender();
		if (hotbarSlot < 0 || hotbarSlot > 8 || mc.player == null) return Attempt.refused(hit.getBlockPos().relative(hit.getDirection()), Check.UNAVAILABLE);
		// Where vanilla puts it: into the clicked block when that's replaceable (air, grass, water) or the item adds to
		// it (a candle, the other half of a slab), otherwise beside it.
		BlockPlaceContext context = placeContext(hit, hotbarSlot);
		BlockPos pos = context.getClickedPos();
		if (airClick(hit, pos) && !o.airPlace()) return Attempt.refused(pos, Check.NO_SUPPORT);
		Check check = spaceCheck(pos, o, context);
		if (!check.ok()) return Attempt.refused(pos, check);
		double eyeHeight = aims.eyeHeightFor(new AABB(hit.getLocation(), hit.getLocation()), o.range());
		if (Double.isNaN(eyeHeight)) return Attempt.refused(pos, Check.OUT_OF_RANGE);
		PlacementAim.Aim aim = new PlacementAim.Aim(hit, eyeHeight);
		if (o.visibleFaces() && !aims.visible(aim)) return Attempt.refused(pos, Check.NOT_VISIBLE);
		return submit(owner, pos, aim, hotbarSlot, o);
	}

	@Override
	public Attempt use(Object owner, BlockHitResult hit, int hotbarSlot, Options o) {
		Threads.assertRender();
		BlockPos pos = hit.getBlockPos().immutable();
		if (hotbarSlot < -1 || hotbarSlot > 8 || mc.player == null || mc.level == null) return Attempt.refused(pos, Check.UNAVAILABLE);
		if (pending.containsKey(pos)) return Attempt.refused(pos, Check.PENDING);
		double eyeHeight = aims.eyeHeightFor(new AABB(pos), o.range());
		if (Double.isNaN(eyeHeight)) return Attempt.refused(pos, Check.OUT_OF_RANGE);
		if (hotbarSlot >= 0 && !handsFree(hotbarSlot)) return Attempt.refused(pos, Check.RATE_LIMITED);
		if (!o.rotate() && (!Myriad.limits().canSend(PacketLimits.Kind.INTERACT, 1) || ActionTiming.get().isLate() && sneakChanges(o)))
			return Attempt.refused(pos, Check.RATE_LIMITED);
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		pending.put(pos, result);
		result.whenComplete((answered, t) -> pending.remove(pos, result));
		if (o.rotate()) queue.add(new Queued(owner, pos, new PlacementAim.Aim(hit, eyeHeight), hotbarSlot, o, result, tick + QUEUE_TICKS, true, null, Float.NaN, Float.NaN));
		else clickUse(hit, hotbarSlot, o, result);
		return new Attempt(pos, Check.OK, result);
	}

	/** Whether a use click with {@code o} needs the sneak key sent the other way round for it (see {@link SneakClick}). */
	private boolean sneakChanges(Options o) {
		return mc.player != null && (mc.player.getLastSentInput().shift() != o.sneak() || mc.player.input.keyPresses.shift() != o.sneak());
	}

	/** Uses the block {@code hit} names now, with hotbar {@code slot}'s item (-1: what's held), and follows the answer. */
	private void clickUse(BlockHitResult hit, int slot, Options o, CompletableFuture<Boolean> result) {
		int before = acks.currentSequence();
		Runnable click = () -> SneakClick.as(o.sneak(), () -> {
			mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
			swing(InteractionHand.MAIN_HAND, o.swing());
		});
		if (slot < 0) click.run();
		else Myriad.inventory().silentSwap(slot, click);
		int sequence = acks.currentSequence();
		if (sequence == before) {
			result.complete(false);
			return;
		}
		acks.track(sequence, hit.getBlockPos()).whenComplete((state, t) -> result.complete(t == null));
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

	private Attempt submit(Object owner, BlockPos pos, PlacementAim.Aim aim, int slot, Options o) {
		if (!o.rotate() && (!handsFree(slot) || airClick(aim.hit(), pos) && !airReady(slot))) return Attempt.refused(pos, Check.RATE_LIMITED);
		// A sneak click's input packets aren't held over to the next tick as the click would be: it waits for a tick's start.
		if (!o.rotate() && o.sneak() && ActionTiming.get().isLate() && BlockInfo.isClickable(mc.level.getBlockState(aim.hit().getBlockPos())))
			return Attempt.refused(pos, Check.RATE_LIMITED);
		BlockPos key = pos.immutable();
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		pending.put(key, result);
		result.whenComplete((placed, t) -> pending.remove(key, result));
		if (o.rotate()) queue.add(new Queued(owner, key, aim, slot, o, result, tick + QUEUE_TICKS, false, null, Float.NaN, Float.NaN));
		else click(key, aim.hit(), slot, o, result);
		return new Attempt(key, Check.OK, result);
	}

	/** Sends the placement now and completes {@code result} with the server's answer. */
	private void click(BlockPos pos, BlockHitResult target, int slot, Options o, CompletableFuture<Boolean> result) {
		int before = acks.currentSequence();
		// What was there: a placement into a replaceable block or onto one it adds to (a snow layer, a candle) leaves
		// something replaceable or the same block, so it's judged by the change.
		BlockState was = mc.level.getBlockState(pos);
		// Clicking the empty space itself: an air placement.
		boolean air = airClick(target, pos);
		if (placing(slot).getItem() instanceof SignItem) signPlacedAt = Clock.millis();
		InventoryManager inventory = MyriadImpl.get().inventoryManager() instanceof InventoryManager i ? i : null;
		if (inventory != null) inventory.slotUsed(slot);
		if (inventory != null && inventory.undoSlotThisTick() == slot) {
			// This slot's block is in the off hand for this tick's air placements: this one goes from there too (against
			// air or a block), with no swap of its own, so a whole burst rides on one swap.
			sneaking(o, target, () -> {
				mc.gameMode.useItemOn(mc.player, InteractionHand.OFF_HAND, target);
				swing(InteractionHand.OFF_HAND, o.swing());
			});
		} else Myriad.inventory().silentSwap(slot, () -> {
			if (air && inventory != null) {
				// Swap the block to the off hand, place it from there, swing that hand, and swap back: how 2b2t clients
				// place against air there, past its Grim. Back first thing next tick: Grim (PacketOrderG) cancels an off
				// hand swap after a placement in the same tick, which would leave the block in your off hand. Meanwhile
				// what was in the off hand (a totem) is in the hand the server holds, where it still saves you. Both
				// slots show what they held until the server has answered, and then the off hand is checked
				// (OffHandWatch).
				inventory.airSwapping(slot);
				inventory.swapHands();
				sneaking(o, target, () -> {
					mc.gameMode.useItemOn(mc.player, InteractionHand.OFF_HAND, target);
					swing(InteractionHand.OFF_HAND, o.swing());
				});
				inventory.afterSwap(inventory::swapHands);
			} else {
				sneaking(o, target, () -> {
					mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, target);
					if (o.swing() && AntiCheat.inEffect().checksSwings()) mc.player.swing(InteractionHand.MAIN_HAND);
				});
			}
		});
		int sequence = acks.currentSequence();
		// useItemOn gives up before sending anything outside the world border.
		if (sequence == before) {
			result.complete(false);
			return;
		}
		acks.track(sequence, pos).whenComplete((state, t) -> result.complete(t == null && !state.isAir() && (!state.canBeReplaced() || !state.equals(was))));
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

	@Override
	public int offHandSlot() {
		return MyriadImpl.get().inventoryManager() instanceof InventoryManager i ? i.undoSlot() : -1;
	}

	@Override
	public boolean canPlaceWith(int hotbarSlot) {
		return handsFree(hotbarSlot);
	}

	/** What clicking {@code hit} with hotbar {@code slot}'s item does as vanilla works it out: where it goes, and whether it can. */
	private BlockPlaceContext placeContext(BlockHitResult hit, int slot) {
		return new BlockPlaceContext(mc.player, InteractionHand.MAIN_HAND, placing(slot), hit);
	}

	/** What a placement from hotbar {@code slot} places: the slot's item, or the off hand's while its block is there for a burst. */
	private ItemStack placing(int slot) {
		boolean inOffHand = MyriadImpl.get().inventoryManager() instanceof InventoryManager i && i.undoSlotThisTick() == slot;
		return inOffHand ? mc.player.getOffhandItem() : mc.player.getInventory().getItem(slot);
	}

	/** Whether clicking {@code hit} to place at {@code pos} clicks empty space (air or a liquid): an air placement. */
	private boolean airClick(BlockHitResult hit, BlockPos pos) {
		if (!hit.getBlockPos().equals(pos) || mc.level == null) return false;
		BlockState clicked = mc.level.getBlockState(pos);
		return clicked.isAir() || clicked.getBlock() instanceof LiquidBlock;
	}

	/**
	 * Runs {@code click} on {@code target} as if sneaking if {@code o} asks for it (see {@link Options#sneak()}) and the
	 * clicked block would act on a plain click; other clicks need no sneak, so they don't send its input packets.
	 */
	private void sneaking(Options o, BlockHitResult target, Runnable click) {
		if (o.sneak() && mc.level != null && BlockInfo.isClickable(mc.level.getBlockState(target.getBlockPos()))) SneakClick.during(click);
		else click.run();
	}

	/** When a module last placed a sign: the editor the server opens for it is kept closed for a moment. */
	private long signPlacedAt = Long.MIN_VALUE / 2;

	/** A sign placed by a module would leave its editor open over the game, stopping whatever placed it: it's kept closed. */
	@Subscribe
	private void onScreenOpen(ScreenOpenEvent e) {
		if (e.screen() instanceof AbstractSignEditScreen && Clock.millis() - signPlacedAt < SIGN_EDITOR_MS) e.cancel();
	}

	/**
	 * Whether a placement with hotbar {@code slot}'s block can go now, as far as the off hand goes: yes if no air
	 * placement's block is in it, or if it's this slot's block, swapped there this tick (it goes from there too). Others
	 * wait until it's back next tick: a swap after a placement in the same tick is what Grim (PacketOrderG) cancels,
	 * which leaves items in the wrong hands.
	 */
	private static boolean handsFree(int slot) {
		if (!(MyriadImpl.get().inventoryManager() instanceof InventoryManager inventory)) return true;
		// A click changed the slot less than a round trip ago (and not this tick): the server may yet show the client its
		// old contents.
		if (slot >= 0 && !inventory.placeable(slot)) return false;
		return !inventory.undoPending() || inventory.undoSlotThisTick() == slot;
	}

	/**
	 * Whether an air placement with hotbar {@code slot}'s block can go now: one that would start a new swap to the off
	 * hand can't follow a click or other action in the same tick (PacketOrderG cancels the swap); one riding this tick's
	 * swap of the same block can.
	 */
	private static boolean airReady(int slot) {
		if (!(MyriadImpl.get().inventoryManager() instanceof InventoryManager inventory)) return true;
		return inventory.undoSlotThisTick() == slot || !inventory.actedThisTick() || !AntiCheat.inEffect().checksPacketOrder();
	}

	/**
	 * Swings {@code hand}: shown if {@code visible}, otherwise only sent (a placement without a swing is noticed), or
	 * not at all where the server doesn't want one ({@link AntiCheat.Profile#checksSwings()}).
	 */
	private void swing(InteractionHand hand, boolean visible) {
		if (!AntiCheat.inEffect().checksSwings()) return;
		if (visible) mc.player.swing(hand);
		else mc.getConnection().send(new ServerboundSwingPacket(hand));
	}

	/**
	 * Asks to face the first queued placement; it's clicked once that rotation has been sent. The look after a solved
	 * click comes first: it has to be this tick's.
	 */
	@Subscribe
	private void onTickStart(TickEvent.Pre e) {
		if (mc.player == null) return;
		if (follow.request(this) || queue.isEmpty()) return;
		Queued first = queue.getFirst();
		if (first.click != null) {
			Myriad.rotations().request(this, first.sendYaw, first.sendPitch, Rotations.PRIORITY_HIGH, MOVE_FIX, null);
			return;
		}
		PlacementAim.Aim aim = first.aim;
		float[] r = anglesFromNextPosition(aim.hit().getLocation(), aim.eyeHeight());
		Myriad.rotations().request(this, r[0], r[1], Rotations.PRIORITY_HIGH, MOVE_FIX, null);
	}

	/**
	 * Aims again just before the rotation goes out, now that you've moved this tick: the yaw is already fixed (walking
	 * follows it), but the pitch can still be exact. Looking down at the side of the block you stand on, a tenth of a
	 * block of error in where your eyes are swings the look by most of a block.
	 */
	@Subscribe(priority = Priority.HIGH)
	private void onBeforeRotationSent(MovementPacketsEvent e) {
		// A solved click's look is exact already, and the look after one isn't to be moved.
		if (queue.isEmpty() || mc.player == null || queue.getFirst().click != null || follow.sent()) return;
		PlacementAim.Aim aim = queue.getFirst().aim;
		float[] r = MathUtil.anglesTo(aims.eyes(aim.eyeHeight()), aim.hit().getLocation());
		Myriad.rotations().request(this, r[0], r[1], REFINED_PRIORITY, MOVE_FIX, null);
	}

	/**
	 * Clicks every queued placement the rotation sent last tick looks at, where the look lands. At the start of the tick,
	 * before this tick's movement: the server already faces that way and your eyes haven't moved since, and it's where
	 * vanilla clicks (Grim flags clicks sent after a movement packet).
	 */
	@Subscribe(priority = RotationManager.ACT_PRIORITY - 10)
	private void onClickTime(TickEvent.Pre e) {
		if (mc.player == null) return;
		float yaw = Myriad.rotations().serverYaw(), pitch = Myriad.rotations().serverPitch();
		float headYaw = Float.isNaN(yawTickAgo) ? yaw : yawTickAgo;
		yawTickAgo = yaw;
		follow.check(yaw, pitch);
		if (queue.isEmpty()) return;
		// Solved clicks and the rest never share a tick: the look after a solved click moves on (to its aim), so a click
		// that only happened to fall under its look would be checked by Grim (RotationPlace) against that next look and
		// fail. Whichever kind is first goes.
		boolean solvedTurn = queue.getFirst().click != null;
		Iterator<Queued> it = queue.iterator();
		while (it.hasNext()) {
			Queued q = it.next();
			if (q.result.isDone()) {
				it.remove();
				continue;
			}
			if ((q.click != null) != solvedTurn) continue;
			if (q.click != null) {
				if (!handsFree(q.slot) || q.click.air() && !airReady(q.slot) || !near(yaw, pitch, q.sendYaw, q.sendPitch)) continue;
				// A block facing where you look (an observer, a piston, a crafter) goes by your head's yaw on the server
				// (LivingEntity.getViewYRot), which turns to your yaw only on the server's tick of you: until a tick has
				// passed, it may still be the yaw before. Held a tick unless the yaw before gives the same.
				if (Math.abs(Mth.wrapDegrees(headYaw - yaw)) > FACING_TOLERANCE && !solver.places(q.click, q.click.hit(), placing(q.slot), headYaw, pitch)) continue;
				it.remove();
				clickSolved(q, yaw, pitch);
				if (follow.inFlight()) break;
				continue;
			}
			if (q.use) {
				if (q.slot >= 0 && !handsFree(q.slot)) continue;
				// Where the look lands on the block, whichever face: the server checks the look is on it.
				BlockHitResult on = Reach.rayHit(yaw, pitch, q.pos, q.options.range());
				if (on == null) continue;
				it.remove();
				if (!Myriad.limits().canSend(PacketLimits.Kind.INTERACT, 1)) {
					q.result.complete(false);
					continue;
				}
				clickUse(new BlockHitResult(on.getLocation(), on.getDirection(), q.pos, false), q.slot, q.options, q.result);
				continue;
			}
			if (!handsFree(q.slot) || airClick(q.target(), q.pos) && !airReady(q.slot)) continue;
			PlacementAim.Aim look = aims.lookHit(yaw, pitch, q.target().getBlockPos(), q.target().getDirection(), q.options.range());
			if (look == null) continue;
			it.remove();
			// The world may have changed while it waited: the click still has to put the block where it was meant to go.
			BlockPlaceContext context = placeContext(look.hit(), q.slot);
			if (!context.canPlace() || !context.getClickedPos().equals(q.pos) || !Myriad.limits().canSend(PacketLimits.Kind.INTERACT, 1)) {
				q.result.complete(false);
				continue;
			}
			click(q.pos, look.hit(), q.slot, q.options, q.result);
		}
	}

	/**
	 * Makes a solved click now that the server has its look: where that look lands on the block when it's meant to, and
	 * only if it still gives what it was solved for with that look. Then the next movement packet's look.
	 */
	private void clickSolved(Queued q, float yaw, float pitch) {
		Click c = q.click;
		BlockHitResult hit = c.hit();
		if (c.lookAtHit()) {
			BlockHitResult look = aims.outlineHit(hit.getBlockPos(), yaw, pitch, q.options.range());
			// Thin blocks (a closed gate, a lever) can be missed by a hair; the cube around them is what servers check.
			if (look == null) look = Reach.rayHit(yaw, pitch, hit.getBlockPos(), q.options.range());
			if (look == null) {
				q.result.complete(false);
				return;
			}
			hit = new BlockHitResult(look.getLocation(), look.getDirection(), hit.getBlockPos(), false);
		}
		if (!solver.places(c, hit, placing(q.slot), yaw, pitch) || !Myriad.limits().canSend(PacketLimits.Kind.INTERACT, 1)) {
			q.result.complete(false);
			return;
		}
		click(q.pos, hit, q.slot, q.options, q.result);
		follow.after(c, q.sendYaw, q.sendPitch, q.result);
	}

	static boolean near(float yaw, float pitch, float toYaw, float toPitch) {
		return Math.abs(Mth.wrapDegrees(yaw - toYaw)) <= FACING_TOLERANCE && Math.abs(pitch - toPitch) <= FACING_TOLERANCE;
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
		follow.clear();
		yawTickAgo = Float.NaN;
	}

	@Override
	public List<BlockHitResult> clickTargets(BlockPos pos) {
		return aims.clickTargets(pos, false);
	}
}
