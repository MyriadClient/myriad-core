package dev.myriadclient.api.service;

import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.hook.Replacement;
import dev.myriadclient.api.hook.Service;
import dev.myriadclient.api.util.Reach;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Shared block placement for modules like Scaffold, Surround or Auto Trap: finds a solid neighbour face to click,
 * optionally rotates for that exact placement, silently swaps to the block, and follows each placement until the
 * server has answered, so modules don't place twice while it catches up and learn whether the block stayed.
 *
 * <pre>{@code
 * Placement.Attempt a = Myriad.placement().place(this, pos, slot, Placement.Options.forServer());
 * if (!a.sent()) return; // a.check() says why: OUT_OF_RANGE, ENTITY_IN_WAY, PENDING, ...
 * a.result().thenAccept(placed -> { if (!placed) warn("The server refused the block at " + pos.toShortString()); });
 * }</pre>
 */
@ApiStatus.NonExtendable
public interface Placement {
	/**
	 * How blocks are placed. Start from a preset ({@link #forServer()}, {@link #DEFAULT}, {@link #STRICT}) and change
	 * what you need with the {@code with} methods, so options added in later versions keep their defaults in your code:
	 *
	 * <pre>{@code
	 * Placement.Options o = Placement.Options.forServer().withRange(range.get());
	 * }</pre>
	 */
	final class Options {
		/** Lenient servers: no rotation, any face. */
		public static final Options DEFAULT = new Options(false, false, true, 4.5, false, false);
		/** Servers that check placements (Grim, NCP, 2b2t): rotate first and click only visible faces. */
		public static final Options STRICT = new Options(true, false, true, 4.5, true, false);

		private final boolean rotate, airPlace, swing, visibleFaces, sneak;
		private final double range;

		private Options(boolean rotate, boolean airPlace, boolean swing, double range, boolean visibleFaces, boolean sneak) {
			this.rotate = rotate;
			this.airPlace = airPlace;
			this.swing = swing;
			this.range = range;
			this.visibleFaces = visibleFaces;
			this.sneak = sneak;
		}

		/** {@link #STRICT} on servers that check placements ({@link AntiCheat#isStrict()}), otherwise {@link #DEFAULT}. */
		public static Options forServer() {
			return AntiCheat.strict() ? STRICT : DEFAULT;
		}

		/**
		 * Face the clicked face server-side first (needed on servers that check placements). The block is placed at
		 * the start of the tick after the rotation went out in the movement packet, before that tick's movement, as
		 * vanilla clicks; placements that a single rotation can't cover wait their turn, one rotation per tick. While
		 * facing it, movement follows the sent yaw (rotation move fix), as Grim expects.
		 */
		public boolean rotate() {
			return rotate;
		}

		/**
		 * Allow placing with no solid neighbour, through the off hand (swap, place, swing, swap back), clicking the
		 * face of the empty space that faces you. This is how 2b2t clients air place there; current Grim builds (2.3.74
		 * on the test server) refuse any placement against air (AirLiquidPlace).
		 */
		public boolean airPlace() {
			return airPlace;
		}

		/** Swing the hand. */
		public boolean swing() {
			return swing;
		}

		/**
		 * How far a click may reach: from the eyes to the point clicked, as the server measures it (Grim also takes the
		 * eye heights of the other poses it can't rule out, which reach farther below you). Use
		 * {@link Reach#blockRange()} for all the server allows.
		 */
		public double range() {
			return range;
		}

		/**
		 * Only click faces that point towards you with nothing in the way; strict anti-cheats reject the rest. Visible
		 * faces are always preferred; this makes them required.
		 */
		public boolean visibleFaces() {
			return visibleFaces;
		}

		/**
		 * Click as if sneaking, without crouching: the server gets the sneak key down for just the click (and up again
		 * straight after, before the tick's movement), so blocks that would open or toggle (chests, doors, repeaters,
		 * note blocks) can be placed against, and a chest stays single beside another. Off, such blocks aren't clicked
		 * unless you're sneaking yourself.
		 */
		public boolean sneak() {
			return sneak;
		}

		public Options withRotate(boolean rotate) {
			return new Options(rotate, airPlace, swing, range, visibleFaces, sneak);
		}

		public Options withAirPlace(boolean airPlace) {
			return new Options(rotate, airPlace, swing, range, visibleFaces, sneak);
		}

		public Options withSwing(boolean swing) {
			return new Options(rotate, airPlace, swing, range, visibleFaces, sneak);
		}

		public Options withRange(double range) {
			return new Options(rotate, airPlace, swing, range, visibleFaces, sneak);
		}

		public Options withVisibleFaces(boolean visibleFaces) {
			return new Options(rotate, airPlace, swing, range, visibleFaces, sneak);
		}

		public Options withSneak(boolean sneak) {
			return new Options(rotate, airPlace, swing, range, visibleFaces, sneak);
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Options x && x.rotate == rotate && x.airPlace == airPlace && x.swing == swing && x.range == range
				&& x.visibleFaces == visibleFaces && x.sneak == sneak;
		}

		@Override
		public int hashCode() {
			return Objects.hash(rotate, airPlace, swing, range, visibleFaces, sneak);
		}

		@Override
		public String toString() {
			return "Placement.Options[rotate=" + rotate + ", airPlace=" + airPlace + ", swing=" + swing + ", range=" + range
				+ ", visibleFaces=" + visibleFaces + ", sneak=" + sneak + "]";
		}
	}

	/** Whether a block could go somewhere now, and if not, why not. */
	enum Check {
		/** It can be placed. */
		OK,
		/** Not in a world, or the hotbar slot isn't 0-8. */
		UNAVAILABLE,
		/** Something that can't be replaced is already there. */
		OCCUPIED,
		/** Farther from the eyes than the range allows. */
		OUT_OF_RANGE,
		/** An entity (or you) stands in the way. */
		ENTITY_IN_WAY,
		/** Nothing solid touches the position to click against, and air placement is off. */
		NO_SUPPORT,
		/** There are faces to click against, but none you can see, and {@link Options#visibleFaces} requires one. */
		NOT_VISIBLE,
		/** A placement or break there is still waiting for its turn or the server's answer. */
		PENDING,
		/**
		 * The shared packet budget is spent for now ({@link PacketLimits}), another item's block is in the off hand for
		 * this tick's air placements (it comes back first thing next tick), or an unrotated {@link Options#sneak()}
		 * placement was asked for after the tick's movement packet; try again next tick.
		 */
		RATE_LIMITED;

		public boolean ok() {
			return this == OK;
		}
	}

	/**
	 * One placement. {@link #sent()} means it was accepted: sent now, or (with {@link Options#rotate}) as soon as the
	 * rotation is out. {@link #result()} completes with true once the server has kept a block at {@link #pos()}, and
	 * false if it refused it, never answered, or the rotation never got through. It completes on the render thread,
	 * and is already false when the placement wasn't accepted.
	 */
	record Attempt(BlockPos pos, Check check, CompletableFuture<Boolean> result) {
		public boolean sent() {
			return check == Check.OK;
		}

		public static Attempt refused(BlockPos pos, Check check) {
			return new Attempt(pos, check, CompletableFuture.completedFuture(false));
		}
	}

	/** Whether a block could go at {@code pos} now: {@link Check#OK}, or the reason it can't. */
	Check check(BlockPos pos, Options options);

	default boolean canPlace(BlockPos pos, Options options) {
		return check(pos, options).ok();
	}

	/** Places the block in {@code hotbarSlot} (0-8) at {@code pos}. The visible selected slot doesn't change. */
	default Attempt place(BlockPos pos, int hotbarSlot, Options options) {
		return place(null, pos, hotbarSlot, options);
	}

	/**
	 * Like {@link #place(BlockPos, int, Options)}, owned by {@code owner}: placements still waiting for their rotation
	 * are dropped by {@link #cancel(Object)} (a module's are when it's disabled).
	 */
	Attempt place(Object owner, BlockPos pos, int hotbarSlot, Options options);

	/**
	 * Places by clicking exactly {@code hit}, for when the face or the click position matters: stairs, slabs, logs and
	 * other blocks whose orientation follows where you click. The block goes where vanilla puts it: beside the clicked
	 * block (offset by the face), or into it when it's replaceable (grass, water; air with {@link Options#airPlace()},
	 * through the off hand, so an air placement can have any face and point) or the item adds to it (candles, sea
	 * pickles, snow layers, the other half of a slab: click the block with the same item). Range, rotation, swing,
	 * sneak and pending checks follow {@code options}.
	 */
	default Attempt place(BlockHitResult hit, int hotbarSlot, Options options) {
		return place(null, hit, hotbarSlot, options);
	}

	Attempt place(Object owner, BlockHitResult hit, int hotbarSlot, Options options);

	/**
	 * Right-clicks the block {@code hit} names, to use it rather than place against it: open a container, toggle a
	 * lever or repeater, add to a composter, use the held item on it. Made the way placements are: with
	 * {@link Options#rotate()} the look turns onto the block first (Grim checks a click on a block as it checks a
	 * placement) and the click goes at the start of the next tick where the look lands on the block, whichever face;
	 * without, at once on {@code hit}. Clicks and placements share one rotation a tick, in the order asked. With
	 * {@link Options#sneak()} the server gets the sneak key down for the click; without, the click goes unsneaked
	 * even while you sneak, so it uses the block instead of placing what you hold. {@link Options#range()} is how far
	 * the click may reach; {@link Options#airPlace()} and {@link Options#visibleFaces()} don't apply.
	 *
	 * <p>The attempt's position is the clicked block; its result completes with true once the server answered the click
	 * (the block as it is after, in the world), false if it was dropped (no look onto the block in time, out of reach,
	 * cancelled, left the world).
	 *
	 * @param hotbarSlot the item to click with, swapped to silently for the click; -1 for whatever you hold
	 */
	default Attempt use(Object owner, BlockHitResult hit, int hotbarSlot, Options options) {
		return Attempt.refused(hit.getBlockPos(), Check.UNAVAILABLE);
	}

	/**
	 * The hotbar slot whose block an air placement swapped into your off hand, or -1. It comes back first thing next
	 * tick (the tick after, for a click held over from after the movement packet); until then, placements with that
	 * slot go from the off hand on the same swap (a burst) and other placements wait. Meanwhile the slot holds what was
	 * in your off hand, so read the block from the off hand.
	 */
	default int offHandSlot() {
		return -1;
	}

	/** Whether a placement with hotbar {@code hotbarSlot}'s item can go now, as far as the off hand goes (see {@link #offHandSlot()}). */
	default boolean canPlaceWith(int hotbarSlot) {
		return true;
	}

	/** Drops {@code owner}'s placements that haven't been sent yet; their results complete with false. */
	void cancel(Object owner);

	/**
	 * Every neighbour face that could be clicked to put a block at {@code pos}: faces you can see first, then nearest
	 * to the eyes. Empty when nothing solid touches it (only air placement would work).
	 */
	List<BlockHitResult> clickTargets(BlockPos pos);

	/** True while a placement at {@code pos} is waiting to be sent or for the server's answer. */
	boolean isPending(BlockPos pos);

	/** A block to place, for {@link #AIM}. */
	record Target(BlockPos pos, Options options) {
	}

	/**
	 * Where to click to place a block: the face and point (core: the best face you can reach and see, near the point
	 * nearest your eyes; for an air placement, the empty space itself), or null if there's nowhere it may click.
	 * Called with the placement's owner as caller. A click elsewhere than core would pick is aimed at from your eyes.
	 */
	Hook<Target, BlockHitResult> AIM = Hook.of("myriad:placement_aim", "Placement aim",
		"Where a placement clicks.");

	/**
	 * The base for a replacement of this service ({@link Service#PLACEMENT}): hands every call
	 * to the service it wraps (core's), so override only what you do differently. While it's in use it gets Myriad's
	 * events and {@link #activated()}/{@link #deactivated()}.
	 */
	abstract class Forwarding implements Placement, Replacement {
		/** The service this one wraps: core's own. */
		protected final Placement core;

		protected Forwarding(Placement core) {
			this.core = Objects.requireNonNull(core);
		}

		@Override
		public Check check(BlockPos pos, Options options) {
			return core.check(pos, options);
		}

		@Override
		public Attempt place(Object owner, BlockPos pos, int hotbarSlot, Options options) {
			return core.place(owner, pos, hotbarSlot, options);
		}

		@Override
		public Attempt place(Object owner, BlockHitResult hit, int hotbarSlot, Options options) {
			return core.place(owner, hit, hotbarSlot, options);
		}

		@Override
		public int offHandSlot() {
			return core.offHandSlot();
		}

		@Override
		public boolean canPlaceWith(int hotbarSlot) {
			return core.canPlaceWith(hotbarSlot);
		}

		@Override
		public void cancel(Object owner) {
			core.cancel(owner);
		}

		@Override
		public List<BlockHitResult> clickTargets(BlockPos pos) {
			return core.clickTargets(pos);
		}

		@Override
		public boolean isPending(BlockPos pos) {
			return core.isPending(pos);
		}
	}
}
