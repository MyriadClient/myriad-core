package com.example.myriadaddon.modules;

import dev.myriadclient.api.module.Categories;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.render.MeshBuilder;
import dev.myriadclient.api.render.Renderer3D;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.RegistryListSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.api.world.BlockScan;
import dev.myriadclient.api.world.ChunkCache;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Highlights every block of the kinds you pick (ores, spawners, portals…) in the chunks around you.
 *
 * <p>Shows the cheap way to draw things that live in the world:
 * <ul>
 *   <li>{@link ChunkCache} works each chunk out once, when it loads, and again only when a block in it changes,
 *   instead of rescanning the area every few ticks;</li>
 *   <li>{@link BlockScan} skips every 16×16×16 section whose palette can't hold a match, so a chunk with no diamonds
 *   costs almost nothing;</li>
 *   <li>the cache's mesher keeps each chunk's boxes on the GPU, so there is no render handler at all: a frame costs
 *   one draw per chunk however many blocks were found;</li>
 *   <li>settings that change what is found call {@code invalidateAll()} (rescan), settings that change how it looks
 *   call {@code remeshAll()} (redraw from what was found, without touching the world).</li>
 * </ul>
 */
public final class BlockSearch extends Module {
	private final RegistryListSetting<Block> blocks = sgGeneral.blocks("Blocks").description("The blocks to highlight.")
		.defaultValue(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.ANCIENT_DEBRIS, Blocks.SPAWNER).build();
	private final IntSetting range = sgGeneral.intSetting("Range").description("Chunks around you.").defaultValue(6).range(1, 16).build();
	private final EnumSetting<Renderer3D.ShapeMode> shape = sgGeneral.enumSetting("Shape", Renderer3D.ShapeMode.BOTH).build();
	private final ColorSetting color = sgGeneral.color("Color").defaultValue(SettingColor.role(SettingColor.Mode.CYAN)).build();
	private final BoolSetting throughWalls = sgGeneral.bool("Through Walls").defaultValue(true).build();

	/** Per chunk: the packed positions ({@link BlockPos#asLong}) of every match, or null when there are none. */
	private final ChunkCache<long[]> found = ChunkCache.of(this, this::scan)
		.range(range::get)
		.mesh(this::mesh)
		.build();

	public BlockSearch() {
		super(Categories.WORLD, "Block Search", "Highlights the blocks you pick through walls.");
		blocks.onChanged(v -> found.invalidateAll());
		shape.onChanged(v -> found.remeshAll());
		color.onChanged(v -> found.remeshAll());
		throughWalls.onChanged(v -> found.remeshAll());
	}

	@Override
	public String hudInfo() {
		int[] count = {0};
		found.forEach(positions -> count[0] += positions.length);
		return String.valueOf(count[0]);
	}

	/** Runs on the client thread once per chunk as it loads or changes. */
	private long[] scan(LevelChunk chunk) {
		Set<Block> targets = blocks.get();
		if (targets.isEmpty()) return null;
		LongArrayList positions = new LongArrayList();
		BlockScan.forEach(chunk, state -> targets.contains(state.getBlock()), (pos, state) -> positions.add(pos.asLong()));
		return positions.isEmpty() ? null : positions.toLongArray();
	}

	/** Runs after each scan, and after remeshAll(): draw in world coordinates, as with Renderer3D. */
	private void mesh(long[] positions, MeshBuilder mesh) {
		int line = color.argb();
		int fill = ColorUtil.withAlpha(line, ColorUtil.alpha(line) / 5);
		for (long p : positions) mesh.blockShape(BlockPos.of(p), fill, line, shape.get(), throughWalls.get());
	}
}
