package com.example.myriadaddon.modules;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.RenderEvent;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.render.RenderSettings;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.RegistryListSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.world.BlockScan;
import dev.myriadclient.api.world.ChunkCache;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.util.Set;

/**
 * Highlights every block of the kinds you pick (ores, spawners, portals…) in the chunks around you.
 *
 * <p>Shows the cheap way to draw things that live in the world:
 * <ul>
 *   <li>{@link ChunkCache} works each chunk out once, when it loads, and again only when a block in it changes,
 *   instead of rescanning the area every few ticks;</li>
 *   <li>{@link BlockScan} skips every 16×16×16 section whose palette can't hold a match, so a chunk with no diamonds
 *   costs almost nothing;</li>
 *   <li>the cache's boxes stay on the GPU, so a frame costs one draw per chunk however many blocks were found: the
 *   render handler only hands the cache over, and core draws it as shader highlights or as boxes, whichever the
 *   player picked in the Render window;</li>
 *   <li>{@link RenderSettings}: the standard Render options (fill, widths, glow, through walls), which core remeshes
 *   on by itself;</li>
 *   <li>settings that change what is found call {@code invalidateAll()} (rescan), a colour calls {@code remeshAll()}
 *   (redraw from what was found, without touching the world).</li>
 * </ul>
 */
public final class BlockSearch extends Module {
	private final RegistryListSetting<Block> blocks = sgGeneral.blocks("Blocks").description("The blocks to highlight.")
		.defaultValue(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.ANCIENT_DEBRIS, Blocks.SPAWNER).build();
	private final IntSetting range = sgGeneral.integer("Range").description("Chunks around you.").defaultValue(6).range(1, 16).build();

	private final SettingGroup sgRender = settings.group("Render");
	private final ColorSetting color = sgRender.color("Color").defaultValue(SettingColor.role(SettingColor.Mode.CYAN)).build();
	private final RenderSettings look = RenderSettings.builder(sgRender).build();

	/** Per chunk: the packed positions ({@link BlockPos#asLong}) of every match, or null when there are none. */
	private final ChunkCache<long[]> found = ChunkCache.of(this, this::scan)
		.range(range::get)
		.boxes(this::boxes, look)
		.build();

	public BlockSearch() {
		super(Tags.WORLD, "Block Search", "Highlights the blocks you pick through walls.");
		blocks.onChanged(v -> found.invalidateAll());
		color.onChanged(v -> found.remeshAll());
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

	/** Runs after each scan, and after remeshAll(): each match's box in world coordinates, with its colour. */
	private void boxes(long[] positions, ChunkCache.BoxSink out) {
		int c = color.argb();
		for (long p : positions) out.box(new AABB(BlockPos.of(p)), c);
	}

	@Subscribe(inGame = true)
	private void onRender(RenderEvent e) {
		e.chunks(found);
	}
}
