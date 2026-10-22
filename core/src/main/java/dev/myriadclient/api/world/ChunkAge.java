package dev.myriadclient.api.world;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.ChunkEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/**
 * Which chunks the server generated just now, as you came near (so nobody had been there), and which it had saved
 * before. It reads the order of each section's block palette as the server sent it:
 * <ul>
 * <li>Saving a chunk rebuilds each section's palette in the order its blocks first appear, from the section's first
 * position (its bottom north-west corner) on. So a section loaded back from disk has, as palette entry 0, the block at
 * that position, and the next new block it meets is entry 1.</li>
 * <li>A section the world generator just filled keeps the palette it grew while generating: air first (every section
 * starts empty), then stone, water and the rest in the order they were placed. Underground, its first position holds
 * stone, deepslate or bedrock, not air.</li>
 * </ul>
 * Only sections with 16 block types or fewer keep the generator's order (a bigger palette is rebuilt in first-appearance
 * order when it grows past that, as saving does). A chunk is fresh when more of its sections say so than say saved.
 * Sections that can't tell (one block type, more than 16, open air at the first position as in the End) don't vote,
 * and a chunk without votes counts as saved: nothing is ever skipped on a guess. A server that rewrites chunks before
 * sending them (some anti-xray) defeats this.
 * <p>
 * The verdict is taken once, when the chunk arrives (blocks changed later append to the palette), and remembered while
 * anyone {@link #use uses} it, until you leave the world. {@link ChunkEvent.Loaded#isFresh()} gives it for the chunk
 * arriving, used or not.
 *
 * <pre>{@code
 * whileEnabled(() -> ChunkAge.use(this), () -> ChunkAge.release(this));
 *
 * if (oldChunksOnly.get() && ChunkAge.isFresh(chunkX, chunkZ)) return;   // nobody was there
 * }</pre>
 */
public final class ChunkAge {
	/** Fresh chunks remembered, at most this many (a long flight sees millions; saved ones are the default). */
	private static final int MAX_REMEMBERED = 1 << 20;
	/** Bits per entry of the small (linear) palette, the only one that keeps the order blocks were added in. */
	private static final int LINEAR_BITS = 4;
	private static final int ENTRIES = 4096;

	private static final ChunkAge LISTENER = new ChunkAge();
	private static final Set<Object> USERS = new HashSet<>();
	private static final LongOpenHashSet FRESH = new LongOpenHashSet();
	private static long seen, seenFresh;
	/** The section's blocks written as the server sent them (see {@link #verdict}); reused, on the render thread. */
	private static final FriendlyByteBuf BUFFER = new FriendlyByteBuf(Unpooled.buffer(4096));

	private ChunkAge() {
	}

	/** Starts remembering chunks' age for {@code owner}, until it {@link #release releases} them; again does nothing. */
	public static void use(Object owner) {
		if (USERS.add(owner) && USERS.size() == 1) Myriad.events().subscribe(LISTENER);
	}

	/** {@code owner} no longer needs them; with nobody left, they're forgotten. */
	public static void release(Object owner) {
		if (!USERS.remove(owner) || !USERS.isEmpty()) return;
		Myriad.events().unsubscribe(LISTENER);
		clear();
	}

	/** Whether the server generated the chunk as it was sent to you (taken when it arrived, while someone used this). */
	public static boolean isFresh(int chunkX, int chunkZ) {
		return FRESH.contains(ChunkPos.pack(chunkX, chunkZ));
	}

	/** Chunks seen since you joined (while used). */
	public static long seen() {
		return seen;
	}

	/** Of those, the fresh ones. */
	public static long seenFresh() {
		return seenFresh;
	}

	/** Whether more of the chunk's sections look freshly generated than look loaded from disk (reads it now). */
	public static boolean isFresh(LevelChunk chunk) {
		int fresh = 0, saved = 0;
		for (LevelChunkSection section : chunk.getSections()) {
			int v = verdict(section);
			if (v > 0) fresh++;
			else if (v < 0) saved++;
		}
		return fresh > saved;
	}

	private static void clear() {
		FRESH.clear();
		seen = seenFresh = 0;
	}

	/** Before anything else scans the chunk. */
	@Subscribe(priority = Priority.HIGHEST)
	private void onLoaded(ChunkEvent.Loaded e) {
		long key = e.pos().pack();
		seen++;
		if (e.isFresh()) {
			seenFresh++;
			if (FRESH.size() >= MAX_REMEMBERED) FRESH.clear();
			FRESH.add(key);
		} else {
			FRESH.remove(key);
		}
	}

	@Subscribe
	private void onWorld(WorldEvent e) {
		clear();
	}

	/**
	 * 1: freshly generated, -1: loaded from disk, 0: can't tell. Only a section with 16 block types or fewer can tell:
	 * one with more had its palette rebuilt in first-appearance order when it outgrew the small one, just as saving does.
	 */
	private static int verdict(LevelChunkSection section) {
		FriendlyByteBuf buf = BUFFER;
		buf.clear();
		section.getStates().write(buf);
		int bits = buf.readByte();
		if (bits != LINEAR_BITS) return 0;
		int size = buf.readVarInt();
		if (size < 2) return 0;
		int[] palette = new int[size];
		for (int i = 0; i < size; i++) palette[i] = buf.readVarInt();
		long[] raw = new long[(ENTRIES + 64 / bits - 1) / (64 / bits)];
		buf.readFixedSizeLongArray(raw);
		SimpleBitStorage storage = new SimpleBitStorage(bits, ENTRIES, raw);
		return verdict(palette, storage::get, ChunkAge::isAir);
	}

	/**
	 * The verdict from a small palette's state ids ({@code palette}), the palette entry at each of the section's
	 * positions ({@code entryAt}) and which state ids are air.
	 */
	static int verdict(int[] palette, IntUnaryOperator entryAt, IntPredicate isAir) {
		// Rebuilt on saving: entry 0 is whatever is at the first position. A generated section's entry 0 is air.
		if (entryAt.applyAsInt(0) != 0) return 1;
		if (!isAir.test(palette[0])) return -1;
		// Air at the first position, as a saved section in open air has too: the next block type met decides. Saved, it
		// is entry 1; generated, it is whatever was placed first, often entry 1 as well, so only a mismatch says fresh.
		for (int i = 1; i < ENTRIES; i++) {
			int id = entryAt.applyAsInt(i);
			if (id != 0) return id == 1 ? 0 : 1;
		}
		return 0;
	}

	private static boolean isAir(int stateId) {
		BlockState state = Block.BLOCK_STATE_REGISTRY.byId(stateId);
		return state != null && state.isAir();
	}
}
