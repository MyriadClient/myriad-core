package com.example.myriadaddon.modules;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.Render3DEvent;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.render.Renderer3D;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.api.world.ChunkCache;
import dev.myriadclient.api.world.Holes;

import java.util.List;

/**
 * Highlights holes you could stand in safely during crystal PvP: green for bedrock, yellow for obsidian and other
 * blast-proof blocks that could still be mined.
 *
 * <p>Shows:
 * <ul>
 *   <li>{@link Holes#scan}: finding holes per chunk, through a {@link ChunkCache} that redoes a chunk only when a
 *       block in it changes (with {@code neighbours()}, since a hole's walls can be in the chunk beside);</li>
 *   <li>a cache without a mesher: the found holes are drawn every frame instead (they're few), so the draw can follow
 *       settings live without remeshing;</li>
 *   <li>{@link Holes#holeOf}: whether you're standing in one, for the module list.</li>
 * </ul>
 */
public final class HoleEsp extends Module {
	private final IntSetting range = sgGeneral.intSetting("Range").description("Chunks around you.").defaultValue(3).range(1, 8).build();
	private final ColorSetting bedrock = sgGeneral.color("Bedrock").defaultValue(SettingColor.role(SettingColor.Mode.GREEN)).build();
	private final ColorSetting obsidian = sgGeneral.color("Obsidian").defaultValue(SettingColor.role(SettingColor.Mode.YELLOW)).build();

	private final ChunkCache<List<Holes.Hole>> holes = ChunkCache.of(this, chunk -> {
			List<Holes.Hole> found = Holes.scan(chunk);
			return found.isEmpty() ? null : found;
		})
		.range(range::get)
		.neighbours()
		.name("Hole ESP")
		.build();

	public HoleEsp() {
		super(Tags.COMBAT, "Hole ESP", "Shows holes you can stand in safely.");
	}

	@Override
	public String hudInfo() {
		if (!inGame()) return null;
		Holes.Hole in = Holes.holeOf(mc.player);
		return in == null ? null : in.safety() == Holes.Safety.BEDROCK ? "safe" : "in hole";
	}

	@Subscribe(inGame = true)
	private void onRender(Render3DEvent e) {
		holes.forEach(list -> {
			for (Holes.Hole h : list) {
				int c = h.safety() == Holes.Safety.BEDROCK ? bedrock.argb() : obsidian.argb();
				e.shapes().box(h.box(), ColorUtil.withAlpha(c, 50), c, Renderer3D.ShapeMode.BOTH, false);
			}
		});
	}
}
