package dev.myriadclient.impl.service;

import net.minecraft.core.BlockPos;

/**
 * Grim's break timing (its FastBreak check), followed from the dig packets sent, so breaks can be finished and started
 * as early as it lets them without being refused.
 * <p>
 * Grim times a break from its start packet: it expects {@code ceil(1 / damage) * 50} ms, where damage is the best
 * progress per tick it has seen for the block it was last told to start (sampled on every movement and swing packet,
 * with the Efficiency it has had back from the server). A finish sooner than that adds the shortfall to a balance
 * (one finish within 25 ms of its time instead takes 10% off it); a start sooner than 275 ms after the last finish adds
 * {@code 300 - gap} to another (a later one takes 10% off). Past 1000 on either, it refuses the packet. Both are kept
 * under a margin here.
 * <p>
 * Air breaks at once (its damage is infinite, so its time is 0), and Grim neither checks that a finish is for the block
 * it was timing nor, on servers with ViaVersion, skips starts on air: after a start on air, any finish passes. That's
 * the decoy start 2b2t clients send (see {@code Breaking.Mode.FAST_GRIM}).
 */
final class GrimBreakTiming {
	/** A start at least this long after the last finish counts as spaced out. */
	static final long GAP_MS = 275;
	private static final double FULL_GAP_MS = 300, SLACK_MS = 25, LIMIT = 1000;
	/** How much of each balance is used, short of Grim's limit. */
	private static final double BREAK_BUDGET = 800, DELAY_BUDGET = 700;

	private BlockPos target;
	private double maxDamage, breakBalance, delayBalance;
	private long startMs, lastFinishMs;

	/** A start was sent for {@code pos}; {@code damage} is the progress per tick Grim credits for it now. */
	void started(BlockPos pos, double damage, long now) {
		long gap = now - lastFinishMs;
		if (gap >= GAP_MS) delayBalance *= 0.9;
		else delayBalance += FULL_GAP_MS - gap;
		delayBalance = clamp(delayBalance);
		startMs = now - (target == null ? 50 : 0);
		target = pos.immutable();
		maxDamage = damage;
	}

	/** Grim samples the block it's timing on every movement and swing packet. */
	void sampled(double damage) {
		if (target != null) maxDamage = Math.max(maxDamage, damage);
	}

	/** A finish was sent (for any block). */
	void finished(long now) {
		if (target == null) return;
		double diff = predictedMs() - (now - startMs);
		breakBalance = clamp(breakBalance);
		if (diff < SLACK_MS) breakBalance *= 0.9;
		else breakBalance += diff;
		lastFinishMs = startMs = now;
	}

	/** The block Grim is timing, or null. */
	BlockPos target() {
		return target;
	}

	/** Whether a finish sent now passes Grim's timing, keeping its balance under the margin. */
	boolean finishOk(long now) {
		if (target == null) return true;
		double diff = predictedMs() - (now - startMs);
		return diff < SLACK_MS || breakBalance + diff <= BREAK_BUDGET;
	}

	/** Whether a start sent now (a real one or a decoy) keeps Grim's start-too-soon balance under the margin. */
	boolean startOk(long now) {
		long gap = now - lastFinishMs;
		return gap >= GAP_MS || delayBalance + (FULL_GAP_MS - gap) <= DELAY_BUDGET;
	}

	/** Whether a start sent straight after a finish (double break's next block) keeps that balance under the margin. */
	boolean startOkRightAfterFinish() {
		return delayBalance + FULL_GAP_MS <= DELAY_BUDGET;
	}

	private double predictedMs() {
		if (maxDamage <= 0) return Double.POSITIVE_INFINITY;
		return Math.ceil(1 / maxDamage) * 50;
	}

	private static double clamp(double balance) {
		return Math.clamp(balance, -LIMIT, LIMIT);
	}

	void reset() {
		target = null;
		maxDamage = breakBalance = delayBalance = 0;
		startMs = lastFinishMs = 0;
	}
}
