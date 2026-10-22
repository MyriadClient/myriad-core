package dev.myriadclient.impl.service;

import dev.myriadclient.api.service.Breaking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;

/** One block {@link BreakManager} was asked to break, and how far it has got. */
final class BreakJob {
	enum Phase {
		/** Waiting its turn. */
		QUEUED,
		/** The server is mining it as its current break. */
		MINING,
		/** Finished early; the server finishes it by itself (double break). */
		DELAYED,
		/** The finishing packet is out; waiting for the server. */
		AWAITING
	}

	final Object owner;
	final BlockPos pos;
	final Breaking.Options options;
	final Block block;
	final long order;
	final CompletableFuture<Boolean> result = new CompletableFuture<>();
	final Breaking.Attempt attempt;
	int priority;
	Phase phase = Phase.QUEUED;
	Direction face = Direction.UP;
	Vec3 aim;
	int started, due, deadline, lastSent;
	/** When the start went out, in wall-clock ms: Grim times breaks in real time. */
	long startedMs;
	float progress;
	boolean acked, finishLater;

	BreakJob(Object owner, BlockPos pos, int priority, Breaking.Options options, Block block, long order) {
		this.owner = owner;
		this.pos = pos;
		this.priority = priority;
		this.options = options;
		this.block = block;
		this.order = order;
		this.attempt = new Breaking.Attempt(pos, Breaking.Check.OK, result);
	}

	/** Mined with its own packets (hand free), rather than as if holding the attack button. */
	boolean packet() {
		return options.mode() != Breaking.Mode.VANILLA;
	}

	/** Finished at 70% (servers that don't check break times, or with Grim's decoy starts). */
	boolean fast() {
		return options.mode() == Breaking.Mode.FAST || grim();
	}

	/** Fast, with decoy starts that hide the speed from Grim. */
	boolean grim() {
		return options.mode() == Breaking.Mode.FAST_GRIM;
	}

	/** Times its finishes and starts by Grim's own allowances (see {@link GrimBreakTiming}). */
	boolean grimTimed() {
		return options.mode() == Breaking.Mode.PACKET || options.mode() == Breaking.Mode.FAST_GRIM;
	}

	/** Waits vanilla's gap after a finish before any start: holding the attack button does. */
	boolean keepsGap() {
		return options.mode() == Breaking.Mode.VANILLA;
	}
}
