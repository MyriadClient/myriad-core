package dev.myriadclient.api.service;

import dev.myriadclient.api.hook.Replacement;
import dev.myriadclient.api.hook.Service;
import dev.myriadclient.api.util.Reach;
import dev.myriadclient.api.util.RenderThread;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Shared block breaking for Nuker, tunnellers, speed mine, auto city and anything else that breaks blocks. Modules
 * ask for a position to be broken and get told when the server has broken it; the service mines one block at a time
 * (two with {@link Options#doubleBreak}) in priority order, breaks instant blocks in batches, picks and holds the
 * fastest tool, rotates if asked, shows the crack, and keeps placements and breaks from overlapping. Breaking runs
 * on its own packets, so it doesn't fight the player's own mining or the attack key.
 *
 * <pre>{@code
 * for (BlockPos pos : Positions.sphere(eyes, 4.5)) {
 *     if (shouldMine(pos)) Myriad.breaking().breakBlock(this, pos, 0, Breaking.Options.DEFAULT);
 * }
 * // Asking again for a position already queued returns the same attempt, so this can run every tick.
 * }</pre>
 *
 * A module's breaks are cancelled when it's disabled.
 */
@ApiStatus.NonExtendable
@RenderThread
public interface Breaking {
	enum Mode {
		/**
		 * Mines like holding the attack button: the tool held the whole time, a swing every tick, vanilla's full time,
		 * one instant break per tick and vanilla's pause between blocks.
		 */
		VANILLA,
		/**
		 * Mines with its own packets, your hand free: the tool is swapped in to start, and held at the server only for
		 * the last few ticks (so its Efficiency counts; the server only credits it once it has ticked with the tool held),
		 * with a swing sent with each packet. Finishes as soon as the vanilla server accepts it (70% of the time) and
		 * Grim's allowance for early finishes lasts, and otherwise at Grim's full time; the next block starts at once
		 * while its allowance for quick starts lasts. Nothing Grim (2b2t) flags. A block put back where the last one was
		 * mined is broken again with a single packet once enough time has passed for it (rebreak).
		 */
		PACKET,
		/**
		 * Finishes the moment the vanilla server would accept it (70% of the time), with no pause between blocks and
		 * optional {@link Options#doubleBreak}. For servers that don't check break times; Grim refuses these finishes
		 * and the blocks come back.
		 */
		FAST,
		/**
		 * {@link #PACKET} that never waits for Grim: once its allowance runs out, a start for the same spot far above
		 * the world goes first (the server ignores it as out of reach, but Grim, on servers with ViaVersion such as 2b2t,
		 * then times air, which breaks at once), and the finish follows the next tick. So every block finishes at 70% of
		 * its time, and double break works too. This is how 2b2t clients mine there. Each
		 * decoy is flagged as breaking air (AirLiquidBreak) and cancelled by current Grim builds.
		 */
		FAST_GRIM
	}

	/**
	 * How blocks are broken. Start from a preset ({@link #forServer()}, {@link #DEFAULT}, {@link #PACKET}, ...) and
	 * change what you need with the {@code with} methods, so options added in later versions keep their defaults in
	 * your code:
	 *
	 * <pre>{@code
	 * Breaking.Options o = Breaking.Options.PACKET.withRotate(rotate.get()).withRange(range.get());
	 * }</pre>
	 */
	final class Options {
		public static final Options DEFAULT = new Options(Mode.VANILLA, false, true, 4.5, true, false, false);
		public static final Options STRICT = new Options(Mode.VANILLA, true, true, 4.5, true, false, false);
		/** Packet mining, Grim-safe: hand free, vanilla's time. */
		public static final Options PACKET = new Options(Mode.PACKET, false, true, 4.5, true, false, false);
		/** For servers that don't check break times. */
		public static final Options FAST = new Options(Mode.FAST, false, true, 4.5, true, true, false);
		/** Fast mining past Grim on servers with ViaVersion (2b2t); experimental. */
		public static final Options FAST_GRIM = new Options(Mode.FAST_GRIM, false, true, 4.5, true, true, false);

		private final Mode mode;
		private final boolean rotate, swing, autoTool, doubleBreak, waitForServer;
		private final double range;

		private Options(Mode mode, boolean rotate, boolean swing, double range, boolean autoTool, boolean doubleBreak, boolean waitForServer) {
			this.mode = Objects.requireNonNull(mode);
			this.rotate = rotate;
			this.swing = swing;
			this.range = range;
			this.autoTool = autoTool;
			this.doubleBreak = doubleBreak;
			this.waitForServer = waitForServer;
		}

		/**
		 * Packet mining with your hand free, as fast as the server allows: {@link #PACKET} where an anti-cheat times
		 * breaks ({@link AntiCheat.BreakTiming#CHECKED}), {@link #FAST_GRIM} with double break where a decoy start fools
		 * its timing ({@link AntiCheat.BreakTiming#DECOYABLE}, 2b2t), otherwise {@link #FAST}.
		 */
		public static Options forServer() {
			return forProfile(AntiCheat.inEffect());
		}

		static Options forProfile(AntiCheat.Profile p) {
			return switch (p.breakTiming()) {
				case SERVER -> FAST;
				case CHECKED -> PACKET;
				case DECOYABLE -> FAST_GRIM;
			};
		}

		/** How blocks are mined; see {@link Mode}. */
		public Mode mode() {
			return mode;
		}

		/** Face the block server-side when starting and finishing (servers that check break direction). */
		public boolean rotate() {
			return rotate;
		}

		/**
		 * Show the hand swinging while mining. Each break packet is sent with a swing either way, as vanilla does (Grim
		 * checks for it); this only decides whether you see it.
		 */
		public boolean swing() {
			return swing;
		}

		/**
		 * Maximum distance from the eyes to the nearest point of the block, as the server and Grim measure (see
		 * {@link Reach#canReach(BlockPos, double)}).
		 */
		public double range() {
			return range;
		}

		/**
		 * Mine with the fastest tool you have, held server-side only (the visible slot stays). One in the inventory is
		 * moved into the hotbar first (while you move, your keys are released for a tick before the click, as Grim
		 * requires), and a break waits a few ticks for it to arrive.
		 */
		public boolean autoTool() {
			return autoTool;
		}

		/** {@link Mode#FAST} and {@link Mode#FAST_GRIM} only: mine a second block while the server finishes the first. */
		public boolean doubleBreak() {
			return doubleBreak;
		}

		/**
		 * Packet modes: leave the block in place on your screen until the server says it's gone, instead of showing it
		 * broken as the packet goes out. A finish the server refuses then never flashes the block away and back (no
		 * ghost blocks to walk into); the cost is a round trip before you see it go.
		 */
		public boolean waitForServer() {
			return waitForServer;
		}

		public Options withMode(Mode mode) {
			return new Options(mode, rotate, swing, range, autoTool, doubleBreak, waitForServer);
		}

		public Options withRotate(boolean rotate) {
			return new Options(mode, rotate, swing, range, autoTool, doubleBreak, waitForServer);
		}

		public Options withSwing(boolean swing) {
			return new Options(mode, rotate, swing, range, autoTool, doubleBreak, waitForServer);
		}

		public Options withRange(double range) {
			return new Options(mode, rotate, swing, range, autoTool, doubleBreak, waitForServer);
		}

		public Options withAutoTool(boolean autoTool) {
			return new Options(mode, rotate, swing, range, autoTool, doubleBreak, waitForServer);
		}

		public Options withDoubleBreak(boolean doubleBreak) {
			return new Options(mode, rotate, swing, range, autoTool, doubleBreak, waitForServer);
		}

		public Options withWaitForServer(boolean waitForServer) {
			return new Options(mode, rotate, swing, range, autoTool, doubleBreak, waitForServer);
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Options x && x.mode == mode && x.rotate == rotate && x.swing == swing && x.range == range
				&& x.autoTool == autoTool && x.doubleBreak == doubleBreak && x.waitForServer == waitForServer;
		}

		@Override
		public int hashCode() {
			return Objects.hash(mode, rotate, swing, range, autoTool, doubleBreak, waitForServer);
		}

		@Override
		public String toString() {
			return "Breaking.Options[mode=" + mode + ", rotate=" + rotate + ", swing=" + swing + ", range=" + range
				+ ", autoTool=" + autoTool + ", doubleBreak=" + doubleBreak + ", waitForServer=" + waitForServer + "]";
		}
	}

	enum Check {
		OK,
		/** Not in a world. */
		UNAVAILABLE,
		/** Air, a fluid or anything else with nothing to break. */
		NOTHING_THERE,
		/** Bedrock and the like, or nothing in reach can break it. */
		UNBREAKABLE,
		/** Farther from the eyes than the range allows. */
		OUT_OF_RANGE,
		/** A placement there is still waiting for the server. */
		PENDING;

		public boolean ok() {
			return this == OK;
		}
	}

	/**
	 * One break. {@link #result()} completes with true once the server has broken the block (or it was already gone),
	 * and false if it refused, the block went out of range, or the break was cancelled. Completes on the render thread.
	 */
	record Attempt(BlockPos pos, Check check, CompletableFuture<Boolean> result) {
		public boolean accepted() {
			return check == Check.OK;
		}

		public static Attempt refused(BlockPos pos, Check check) {
			return new Attempt(pos, check, CompletableFuture.completedFuture(false));
		}
	}

	/** Whether {@code pos} could be broken now, or why not. */
	Check check(BlockPos pos, Options options);

	/**
	 * Queues {@code pos} to be broken. Higher {@code priority} goes first, then first come first served. Asking again
	 * for a position that's already queued returns its attempt (raising its priority if yours is higher).
	 */
	Attempt breakBlock(Object owner, BlockPos pos, int priority, Options options);

	/** Stops and forgets every break {@code owner} asked for. */
	void cancel(Object owner);

	/** Stops the break at {@code pos}, whoever asked for it. */
	void cancel(BlockPos pos);

	/** True while a break at {@code pos} is queued, being mined, or waiting for the server. */
	boolean isPending(BlockPos pos);

	/** How far mining {@code pos} has got, 0 to 1 (1 = the finish has been sent); 0 if it isn't being mined. */
	float progress(BlockPos pos);

	/** Whether a block at {@code pos} can be broken now by packet mining's rebreak (a single finish packet). */
	boolean canRebreak(BlockPos pos);

	/**
	 * The base for a replacement of this service ({@link Service#BREAKING}): hands every call
	 * to the service it wraps (core's), so override only what you do differently. While it's in use it gets Myriad's
	 * events and {@link #activated()}/{@link #deactivated()}.
	 */
	abstract class Forwarding implements Breaking, Replacement {
		/** The service this one wraps: core's own. */
		protected final Breaking core;

		protected Forwarding(Breaking core) {
			this.core = Objects.requireNonNull(core);
		}

		@Override
		public Check check(BlockPos pos, Options options) {
			return core.check(pos, options);
		}

		@Override
		public Attempt breakBlock(Object owner, BlockPos pos, int priority, Options options) {
			return core.breakBlock(owner, pos, priority, options);
		}

		@Override
		public void cancel(Object owner) {
			core.cancel(owner);
		}

		@Override
		public void cancel(BlockPos pos) {
			core.cancel(pos);
		}

		@Override
		public boolean isPending(BlockPos pos) {
			return core.isPending(pos);
		}

		@Override
		public float progress(BlockPos pos) {
			return core.progress(pos);
		}

		@Override
		public boolean canRebreak(BlockPos pos) {
			return core.canRebreak(pos);
		}
	}
}
