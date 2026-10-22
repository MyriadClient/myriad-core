package dev.myriadclient.harness;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The fake level's chunk source: every chunk is loaded, as an empty chunk, so the game's "is it loaded" checks pass;
 * blocks themselves are the fake level's to answer. Made with {@link Fields#allocate}.
 */
final class FakeChunks extends ClientChunkCache {
	private FakeLevel level;
	private Holder<Biome> biome;
	private Long2ObjectOpenHashMap<LevelChunk> chunks;

	@SuppressWarnings("unused")
	private FakeChunks() {
		super(null, 0);
	}

	static FakeChunks create(FakeLevel level, Holder<Biome> biome) {
		FakeChunks c = Fields.allocate(FakeChunks.class);
		c.level = level;
		c.biome = biome;
		c.chunks = new Long2ObjectOpenHashMap<>();
		return c;
	}

	@Override
	public @Nullable LevelChunk getChunk(int x, int z, ChunkStatus status, boolean create) {
		return chunks.computeIfAbsent(ChunkPos.pack(x, z), k -> new EmptyLevelChunk(level, new ChunkPos(x, z), biome));
	}

	@Override
	public int getLoadedChunksCount() {
		return chunks.size();
	}
}
