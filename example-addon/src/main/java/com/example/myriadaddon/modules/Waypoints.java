package com.example.myriadaddon.modules;

import com.example.myriadaddon.waypoints.Waypoint;
import com.example.myriadaddon.waypoints.WaypointStore;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.Render2DEvent;
import dev.myriadclient.api.event.events.Render3DEvent;
import dev.myriadclient.api.event.events.ScreenOpenEvent;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.render.Projection;
import dev.myriadclient.api.render.Renderer3D;
import dev.myriadclient.api.render.WorldLabel;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.api.util.Format;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws your waypoints in the world: a box and a beam on the block, and a label with the name and distance that stays
 * on screen however far away the waypoint is. Optionally drops a waypoint where you die.
 *
 * <p>Shows how a module:
 * <ul>
 *   <li>gets shared state through its constructor (the {@link WaypointStore}) instead of a static;</li>
 *   <li>splits its settings into groups (the first is open by default, the rest start folded);</li>
 *   <li>defaults colours to theme roles, so it matches whatever theme the player uses;</li>
 *   <li>draws 3D shapes in {@link Render3DEvent} and 2D labels in {@link Render2DEvent};</li>
 *   <li>reacts to vanilla through an event ({@link ScreenOpenEvent}) rather than a mixin when one exists.</li>
 * </ul>
 */
public final class Waypoints extends Module {
	private final WaypointStore store;

	// General: the first group, open when the player unfolds the module.
	private final IntSetting maxDistance = sgGeneral.integer("Max Distance").description("Hide waypoints further than this (0 shows all).")
		.defaultValue(0).range(0, 100_000).sliderRange(0, 10_000).build();
	private final BoolSetting deathWaypoints = sgGeneral.bool("Death Waypoints").description("Add a \"Death\" waypoint where you die.").defaultValue(true).build();

	private final SettingGroup sgWorld = settings.group("In World");
	private final BoolSetting box = sgWorld.bool("Box").defaultValue(true).build();
	private final BoolSetting beam = sgWorld.bool("Beam").description("A line up into the sky from the waypoint.").defaultValue(true).build();
	private final IntSetting beamHeight = sgWorld.integer("Beam Height").defaultValue(64).range(1, 320).visible(beam::get).build();
	private final BoolSetting throughWalls = sgWorld.bool("Through Walls").defaultValue(true).build();
	// A theme role as the default: the colour follows the theme until the player picks their own.
	private final ColorSetting color = sgWorld.color("Color").description("For waypoints without a colour of their own.")
		.defaultValue(SettingColor.role(SettingColor.Mode.ACCENT)).build();

	private final SettingGroup sgLabels = settings.group("Labels");
	private final BoolSetting labels = sgLabels.bool("Labels").defaultValue(true).build();
	private final BoolSetting distance = sgLabels.bool("Distance").defaultValue(true).visible(labels::get).build();
	private final BoolSetting coords = sgLabels.bool("Coordinates").visible(labels::get).build();
	private final EnumSetting<WorldLabel.Background> background = sgLabels.oneOf("Background", WorldLabel.Background.ROUNDED).visible(labels::get).build();
	private final DoubleSetting scale = sgLabels.decimal("Scale").defaultValue(1).range(0.5, 3).decimals(1).visible(labels::get).build();

	public Waypoints(WaypointStore store) {
		super(Tags.WORLD, "Waypoints", "Shows saved positions in the world. Manage them with .waypoint or the Waypoints window.");
		this.store = store;
	}

	/** Shown next to the module in the module list. */
	@Override
	public String hudInfo() {
		return String.valueOf(store.inDimension().size());
	}

	private List<Waypoint> shown() {
		if (!inGame()) return List.of();
		Vec3 eye = mc.player.getEyePosition();
		int max = maxDistance.get();
		List<Waypoint> list = new ArrayList<>();
		for (Waypoint w : store.inDimension()) {
			if (w.visible() && (max == 0 || w.center().distanceTo(eye) <= max)) list.add(w);
		}
		return list;
	}

	private int colorOf(Waypoint w) {
		return w.color() != 0 ? w.color() : color.argb();
	}

	@Subscribe
	private void onRender3D(Render3DEvent e) {
		for (Waypoint w : shown()) {
			int c = colorOf(w);
			if (box.get()) Renderer3D.box(new AABB(w.pos()), ColorUtil.withAlpha(c, 40), c, Renderer3D.ShapeMode.BOTH, throughWalls.get());
			if (beam.get()) {
				Vec3 base = w.center();
				Renderer3D.line(base, base.add(0, beamHeight.get(), 0), c, ColorUtil.withAlpha(c, 0), throughWalls.get());
			}
		}
	}

	@Subscribe
	private void onRender2D(Render2DEvent e) {
		if (!labels.get()) return;
		Vec3 camera = Projection.camera();
		for (Waypoint w : shown()) {
			double dist = w.center().distanceTo(camera);
			// Far points can sit beyond the far plane; pull the label in along the same line of sight so it still shows.
			Vec3 at = w.center().add(0, 1, 0);
			if (dist > 96) at = camera.add(at.subtract(camera).normalize().scale(96));

			List<WorldLabel.Segment> segments = new ArrayList<>();
			segments.add(new WorldLabel.Segment(w.name(), colorOf(w)));
			if (distance.get()) segments.add(new WorldLabel.Segment(Format.distance(dist), theme().text.argb()));
			if (coords.get()) segments.add(new WorldLabel.Segment(w.coords(), theme().textDim.argb()));
			float s = scale.getFloat() * WorldLabel.distanceScale(at);
			WorldLabel.draw(e.canvas(), at, s, segments, background.get(), theme().windowBackground.argb(), ColorUtil.withAlpha(colorOf(w), 120), true);
		}
	}

	@Subscribe
	private void onScreen(ScreenOpenEvent e) {
		if (!(e.screen() instanceof DeathScreen) || !deathWaypoints.get() || !inGame()) return;
		store.put("Death", mc.player.blockPosition());
		info("Saved where you died as \"Death\".");
	}

	private static ThemeSettings theme() {
		return Myriad.ui().theme();
	}
}
