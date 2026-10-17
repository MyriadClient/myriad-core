package dev.myriadclient.api.build;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A running build from {@code Myriad.building().start(...)}: the plan for what's in reach, how much is left, and a
 * way to stop it. Draw {@link #steps()} to show the plan; each step says what's happening there or why it's stuck.
 */
@ApiStatus.NonExtendable
public interface Build {
	/** Where one position stands. */
	enum Status {
		/** Already what the blueprint wants. */
		DONE,
		/** Will be broken (or is queued to be). */
		BREAK,
		/** Will be placed. */
		PLACE,
		/** An action there is waiting for its turn or for the server, or material is being moved to the hotbar. */
		WAITING,
		/** Out of reach from where you are. */
		OUT_OF_RANGE,
		/** No item in the inventory fits. */
		NO_ITEM,
		/** Nothing to place against yet; it waits for a neighbour to be placed first. */
		NO_SUPPORT,
		/** Only faces you can't see to place against, and the placement options require visible ones. */
		NOT_VISIBLE,
		/** An entity (or you) stands in the way. */
		ENTITY_IN_WAY,
		/** The wrong block is there and the build isn't allowed to break it. */
		BLOCKED,
		/** The wrong block is there and it can't be broken. */
		UNBREAKABLE,
		/** Breaking it would let a fluid flow in, and the build avoids that. */
		FLUID,
		/** It's the block you're standing on. */
		SUPPORTS_YOU,
		/** No click from here places it facing the right way; move and it may become possible. */
		WRONG_ANGLE
	}

	record Step(BlockPos pos, Target target, Status status) {
	}

	/** The positions in reach as of this tick, with what's happening at each. */
	List<Step> steps();

	/**
	 * Positions in the whole blueprint that aren't done yet (unloaded ones count), as of the last full check: one is
	 * made when nothing in reach is left to do, at most every 5 seconds. -1 before the first.
	 */
	int remaining();

	/** Whether everything in the blueprint is done (as of the last full check). */
	boolean isDone();

	boolean isRunning();

	void stop();

	/**
	 * Completes with true when the build finishes (it isn't kept up, and everything is done) and false when it's
	 * stopped first. Completes on the render thread.
	 */
	CompletableFuture<Boolean> finished();
}
