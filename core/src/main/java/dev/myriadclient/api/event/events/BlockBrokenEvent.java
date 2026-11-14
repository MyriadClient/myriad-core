package dev.myriadclient.api.event.events;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

/**
 * You finished breaking a block (the client removed it; the server confirms with a {@link BlockUpdateEvent}).
 * {@link #state()} is what was there. Posted on the render thread right after the client broke it, by your own mining
 * or a module's; not for blocks others break. Not cancellable.
 */
public final class BlockBrokenEvent {
	private final BlockPos pos;
	private final BlockState state;

	/** Core's; only Myriad makes these. */
	@ApiStatus.Internal
	public BlockBrokenEvent(BlockPos pos, BlockState state) {
		this.pos = pos;
		this.state = state;
	}

	/** Where the block was. */
	public BlockPos pos() {
		return pos;
	}

	/** The block that was there before it broke. */
	public BlockState state() {
		return state;
	}
}
