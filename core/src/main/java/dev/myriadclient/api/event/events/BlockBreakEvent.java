package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.ApiStatus;

/** The local player's block breaking. */
public abstract class BlockBreakEvent extends Cancellable {
	private final BlockPos pos;
	private final Direction direction;

	protected BlockBreakEvent(BlockPos pos, Direction direction) {
		this.pos = pos;
		this.direction = direction;
	}

	public BlockPos pos() {
		return pos;
	}

	public Direction direction() {
		return direction;
	}

	/** The player started hitting a block. Cancel to stop vanilla mining it (e.g. to mine it with packets instead). */
	public static final class Start extends BlockBreakEvent {
		@ApiStatus.Internal
		public Start(BlockPos pos, Direction direction) {
			super(pos, direction);
		}
	}

	/** Vanilla is about to advance its mining progress on a block (each tick the attack key is held). Cancellable. */
	public static final class Progress extends BlockBreakEvent {
		@ApiStatus.Internal
		public Progress(BlockPos pos, Direction direction) {
			super(pos, direction);
		}
	}
}
