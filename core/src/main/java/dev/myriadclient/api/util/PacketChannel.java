package dev.myriadclient.api.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.CompletableFuture;

/** What {@link Packets} sends through, so the API needs no part of core's networking to load. Use {@link Packets}; this is core's. */
@ApiStatus.Internal
public interface PacketChannel {
	/** Runs {@code send} with {@code PacketEvent.Send} and {@code AttackEvent} not posted for what it sends. */
	void runSilently(Runnable send);

	/** The server's answer to the sequenced action just sent, for the block at {@code pos}. */
	CompletableFuture<BlockState> answerTo(BlockPos pos);
}
