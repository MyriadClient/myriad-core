package dev.myriadclient.impl.network;

import dev.myriadclient.api.util.PacketChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.CompletableFuture;

/** {@link PacketChannel}: {@code Packets} onto the packet gate and the block acknowledgement tracker. */
public final class CorePacketChannel implements PacketChannel {
	private final BlockAckTracker acks;

	public CorePacketChannel(BlockAckTracker acks) {
		this.acks = acks;
	}

	@Override
	public void runSilently(Runnable send) {
		PacketGate.runSilently(send);
	}

	@Override
	public CompletableFuture<BlockState> answerTo(BlockPos pos) {
		return acks.track(acks.currentSequence(), pos);
	}
}
