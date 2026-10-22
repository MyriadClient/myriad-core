package dev.myriadclient.api.util;

import dev.myriadclient.api.Myriad;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/** Sending packets to the server. All of these do nothing outside a world. */
public final class Packets {
	private Packets() {
	}

	/** Sends a packet like vanilla would: other features see it in {@code PacketEvent.Send} and may change or cancel it. */
	public static void send(Packet<?> packet) {
		var handler = Minecraft.getInstance().getConnection();
		if (handler != null) handler.send(packet);
	}

	/**
	 * Sends a packet without posting {@code PacketEvent.Send} or {@code AttackEvent}. Use it for packets that are
	 * part of your own trick (a crit hop, a desync fix) and that other features shouldn't react to or rewrite.
	 */
	public static void sendSilently(Packet<?> packet) {
		if (Myriad.isReady()) Myriad.packets().runSilently(() -> send(packet));
	}

	/**
	 * Sends a block or item action that the server acknowledges by sequence number (block breaking actions, using an
	 * item, interacting with a block), keeping the client's prediction in step. Without the sequence the server's
	 * reply can undo the client's view of the change.
	 *
	 * <pre>{@code
	 * Packets.sendSequenced(seq -> new PlayerActionC2SPacket(Action.START_DESTROY_BLOCK, pos, side, seq));
	 * }</pre>
	 */
	public static void sendSequenced(PredictiveAction creator) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameMode != null && mc.level != null) mc.gameMode.startPrediction(mc.level, creator);
	}

	/**
	 * Like {@link #sendSequenced(PredictiveAction)}, and tells you what the server made of it: the future completes
	 * with the server's block state at {@code pos} once it has answered (air after a break it accepted, the old block
	 * if it refused). It fails with a {@link TimeoutException} if the server never answers, and
	 * with a {@link CancellationException} if you leave the world or aren't in one. Completes on the render thread.
	 *
	 * <pre>{@code
	 * Packets.sendSequenced(pos, seq -> new ServerboundPlayerActionPacket(Action.STOP_DESTROY_BLOCK, pos, side, seq))
	 *     .thenAccept(state -> { if (!state.isAir()) warn("The server kept the block"); });
	 * }</pre>
	 */
	public static CompletableFuture<BlockState> sendSequenced(BlockPos pos, PredictiveAction creator) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameMode == null || mc.level == null || !Myriad.isReady()) return CompletableFuture.failedFuture(new CancellationException("Not in a world"));
		sendSequenced(creator);
		return Myriad.packets().answerTo(pos);
	}
}
