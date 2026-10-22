package dev.myriadclient.api.event.events;

import dev.myriadclient.api.world.ChunkAge;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;

/** Chunks arriving from and leaving the client world. Posted on the render thread. */
public abstract class ChunkEvent {
	private final ChunkPos pos;

	protected ChunkEvent(ChunkPos pos) {
		this.pos = pos;
	}

	public ChunkPos pos() {
		return pos;
	}

	/** A chunk's blocks and block entities arrived. Scan it here instead of rescanning the world. */
	public static final class Loaded extends ChunkEvent {
		private final LevelChunk chunk;
		/** 1 fresh, -1 saved, 0 not read yet. */
		private int fresh;

		@ApiStatus.Internal
		public Loaded(LevelChunk chunk) {
			super(chunk.getPos());
			this.chunk = chunk;
		}

		public LevelChunk chunk() {
			return chunk;
		}

		/**
		 * Whether the server generated the chunk just now, as you came near, rather than loading it saved: see
		 * {@link ChunkAge}. Read from the blocks as they arrived, once, on the first call.
		 */
		public boolean isFresh() {
			if (fresh == 0) fresh = ChunkAge.isFresh(chunk) ? 1 : -1;
			return fresh > 0;
		}
	}

	/** A chunk is being dropped from the client. */
	public static final class Unloaded extends ChunkEvent {
		@ApiStatus.Internal
		public Unloaded(ChunkPos pos) {
			super(pos);
		}
	}
}
