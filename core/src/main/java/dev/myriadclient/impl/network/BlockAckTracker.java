package dev.myriadclient.impl.network;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
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
	private record Pending(int sequence, BlockPos pos, long deadline, CompletableFuture<BlockState> future) {
	}

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
		pending.add(new Pending(sequence, pos.immutable(), Clock.millis() + timeoutMs(), future));
		return future;
	}

	/** Generous enough for a laggy server: a couple of round trips plus a few slow ticks. */
	private static long timeoutMs() {
		int ping = Myriad.isReady() ? Myriad.server().ping() : 0;
		return Math.min(10_000, 1_500 + 3L * ping);
	}

	/** Called after the client applied the server's acknowledgement of every action up to {@code sequence}. */
	public void onAck(int sequence) {
		if (pending.isEmpty() || mc.level == null) return;
		List<Pending> done = new ArrayList<>();
		pending.removeIf(p -> p.sequence <= sequence && done.add(p));
		for (Pending p : done) p.future.complete(mc.level.getBlockState(p.pos));
	}

	@Subscribe
	private void onTick(TickEvent.Post e) {
		if (pending.isEmpty()) return;
		long now = Clock.millis();
		List<Pending> expired = new ArrayList<>();
		pending.removeIf(p -> now > p.deadline && expired.add(p));
		for (Pending p : expired) p.future.completeExceptionally(new TimeoutException("No answer from the server for the action at " + p.pos.toShortString()));
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		List<Pending> all = new ArrayList<>(pending);
		pending.clear();
		for (Pending p : all) p.future.completeExceptionally(new CancellationException("Left the world"));
	}
}
