package dev.myriadclient.api.event.events;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

/**
 * The server changed a block: single updates and multi-block updates alike, including confirmations of blocks you
 * placed or broke. Posted on the render thread just before the change is applied, so the world still holds
 * {@link #oldState()}. Only while something listens. Not cancellable.
 */
public final class BlockUpdateEvent {
	private final BlockPos pos;
	private final BlockState oldState, newState;

	/** Core's; only Myriad makes these. */
	@ApiStatus.Internal
	public BlockUpdateEvent(BlockPos pos, BlockState oldState, BlockState newState) {
		this.pos = pos;
		this.oldState = oldState;
		this.newState = newState;
	}

	/** Where the block changed. */
	public BlockPos pos() {
		return pos;
	}

	/** What the client had there before the update (your own prediction, for a block you just placed or broke). */
	public BlockState oldState() {
		return oldState;
	}

	/** What the server says is there now. */
	public BlockState newState() {
		return newState;
	}
}
