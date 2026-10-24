package dev.myriadclient.impl.network;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.util.Ticks;
import dev.myriadclient.impl.util.Clock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * Tells block actions (placing, breaking, using an item on a block) when the server has answered them.
 *
 * <p>Every such action carries a sequence number. While it is unanswered the client shows its own guess and files
 * the server's block updates away; the server's acknowledgement of that sequence arrives after its block updates
 * for the action (block changes go out with the level tick, acks after it), and vanilla then puts the server's state
 * into the world. So at the moment the ack is handled, the world holds the server's verdict at that position.
 */
public final class BlockAckTracker {
	/** {@code droppedAt}: when it counts as dropped, once the server has answered something sent after it (0: not yet). */
	private static final class Pending {
		final int sequence;
		final BlockPos pos;
		final long deadline, sentTick;
		final CompletableFuture<BlockState> future;
		long droppedAt;

		Pending(int sequence, BlockPos pos, long deadline, long sentTick, CompletableFuture<BlockState> future) {
			this.sequence = sequence;
			this.pos = pos;
			this.deadline = deadline;
			this.sentTick = sentTick;
			this.future = future;
		}
	}

	/**
	 * How long an action sent no later than one the server has acknowledged may still be answered: the server can
	 * handle one tick's packets across two of its own, each acknowledged at its end.
	 */
	private static final long DROP_GRACE_MS = 150;

	private final Minecraft mc = Minecraft.getInstance();
	private final List<Pending> pending = new ArrayList<>();

	/** The sequence number the last predicted block action was sent with, or -1 outside a world. */
	public int currentSequence() {
		return mc.level instanceof PredictionAccess access ? access.myriad$currentSequence() : -1;
	}

	/**
	 * Completes with the server's block state at {@code pos} once it has answered action {@code sequence}, or fails
	 * with a {@link TimeoutException} if it doesn't answer in time.
	 */
	public CompletableFuture<BlockState> track(int sequence, BlockPos pos) {
		CompletableFuture<BlockState> future = new CompletableFuture<>();
		pending.add(new Pending(sequence, pos.immutable(), Clock.millis() + timeoutMs(), Ticks.current(), future));
		return future;
	}

	/** Generous enough for a laggy server: a couple of round trips plus a few slow ticks. */
	private static long timeoutMs() {
		int ping = Myriad.isReady() ? Myriad.server().ping() : 0;
		return Math.min(10_000, 1_500 + 3L * ping);
	}

	/**
	 * Called after the client applied the server's acknowledgement of every action up to {@code sequence}. Actions still
	 * unanswered that went out in the same tick as one it covers (or before) were dropped, by Paper's limit of 9 uses
	 * in 300 ms most likely: the server answers all it handled of a tick's packets at once. They're given up on after
	 * {@link #DROP_GRACE_MS}.
	 */
	public void onAck(int sequence) {
		if (pending.isEmpty() || mc.level == null) return;
		List<Pending> done = new ArrayList<>();
		pending.removeIf(p -> p.sequence <= sequence && done.add(p));
		long answeredTick = Long.MIN_VALUE;
		for (Pending p : done) answeredTick = Math.max(answeredTick, p.sentTick);
		long dropAt = Clock.millis() + DROP_GRACE_MS;
		for (Pending p : pending) if (p.droppedAt == 0 && p.sentTick <= answeredTick) p.droppedAt = dropAt;
		for (Pending p : done) p.future.complete(mc.level.getBlockState(p.pos));
	}

	@Subscribe
	private void onTick(TickEvent.Post e) {
		if (pending.isEmpty() || mc.level == null) return;
		long now = Clock.millis();
		List<Pending> dropped = new ArrayList<>(), expired = new ArrayList<>();
		pending.removeIf(p -> p.droppedAt != 0 && now >= p.droppedAt && dropped.add(p));
		pending.removeIf(p -> now > p.deadline && expired.add(p));
		if (dropped.isEmpty() && expired.isEmpty()) return;
		// Nothing will acknowledge them now, so the client's guess (a block shown where the server has none) would stay:
		// put back what the server last had there, as its acknowledgement would have.
		int last = -1;
		for (Pending p : dropped) last = Math.max(last, p.sequence);
		for (Pending p : expired) last = Math.max(last, p.sequence);
		if (mc.level instanceof PredictionAccess access) access.myriad$endPredictionsUpTo(last);
		for (Pending p : dropped) p.future.complete(mc.level.getBlockState(p.pos));
		for (Pending p : expired) p.future.completeExceptionally(new TimeoutException("No answer from the server for the action at " + p.pos.toShortString()));
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		List<Pending> all = new ArrayList<>(pending);
		pending.clear();
		for (Pending p : all) p.future.completeExceptionally(new CancellationException("Left the world"));
	}
}
