package dev.myriadclient.api.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.function.Predicate;

/**
 * Fast block searches over a chunk. Each 16×16×16 section keeps a palette of the states it holds, so a section that
 * can't contain a match is skipped without reading a single block: looking for obsidian or ores usually touches a
 * handful of sections instead of the ~100,000 blocks of a full chunk. Pair it with {@link ChunkCache}, which calls you
 * once per chunk as chunks load and change.
 *
 * <pre>{@code
 * BlockScan.forEach(chunk, state -> state.is(Blocks.ANCIENT_DEBRIS), (pos, state) -> found.add(pos.immutable()));
 * }</pre>
 */
public final class BlockScan {
	/** Receives each match. {@code pos} is reused between calls: call {@code pos.immutable()} to keep it. */
	@FunctionalInterface
	public interface Visitor {
		void visit(BlockPos.MutableBlockPos pos, BlockState state);
	}

	private BlockScan() {
	}

	/** Visits every block in {@code chunk} whose state matches {@code filter}, bottom to top. */
	public static void forEach(LevelChunk chunk, Predicate<BlockState> filter, Visitor visitor) {
		LevelChunkSection[] sections = chunk.getSections();
		int baseX = chunk.getPos().x() << 4, baseZ = chunk.getPos().z() << 4;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			// maybeHas reads only the palette; an all-air section answers with filter.test(air).
			if (section == null || !section.maybeHas(filter)) continue;
			int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (filter.test(state)) visitor.visit(pos.set(baseX + x, baseY + y, baseZ + z), state);
					}
				}
			}
		}
	}

	/** True if any block in {@code chunk} matches, checking palettes first. */
	public static boolean contains(LevelChunk chunk, Predicate<BlockState> filter) {
		for (LevelChunkSection section : chunk.getSections()) {
			if (section == null || !section.maybeHas(filter)) continue;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) if (filter.test(section.getBlockState(x, y, z))) return true;
				}
			}
		}
		return false;
	}
}
