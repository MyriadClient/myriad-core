package dev.myriadclient.api.event.events;

import dev.myriadclient.api.world.ChunkAge;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;

/**
 * Chunks arriving from and leaving the client world. Posted on the render thread, only while something listens. Not
 * cancellable.
 */
public abstract class ChunkEvent {
	private final ChunkPos pos;

	/** Core's; addons don't make these. */
	protected ChunkEvent(ChunkPos pos) {
		this.pos = pos;
	}

	/** Which chunk. */
	public ChunkPos pos() {
		return pos;
	}

	/** A chunk's blocks and block entities arrived. Scan it here instead of rescanning the world. */
	public static final class Loaded extends ChunkEvent {
		private final LevelChunk chunk;
		/** 1 fresh, -1 saved, 0 not read yet. */
		private int fresh;

		/** Core's; only Myriad makes these. */
		@ApiStatus.Internal
		public Loaded(LevelChunk chunk) {
			super(chunk.getPos());
			this.chunk = chunk;
		}

		/** The chunk as the client now holds it. */
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

	/** A chunk is about to be dropped from the client (out of view distance, or the server unloaded it); still readable. */
	public static final class Unloaded extends ChunkEvent {
		/** Core's; only Myriad makes these. */
		@ApiStatus.Internal
		public Unloaded(ChunkPos pos) {
			super(pos);
		}
	}
}
