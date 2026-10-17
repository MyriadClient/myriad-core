package com.example.myriadaddon.hud;

import com.example.myriadaddon.waypoints.Waypoint;
import com.example.myriadaddon.waypoints.WaypointStore;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.ui.hud.HudStyle;
import dev.myriadclient.api.ui.hud.TextHudPanel;
import dev.myriadclient.api.util.Format;
import java.util.Optional;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** The closest waypoint, how far it is, and an arrow pointing to it relative to where you're looking. */
public final class NearestWaypointHud extends TextHudPanel {
	private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

	private final WaypointStore store;
	private final BoolSetting arrow = sgGeneral.bool("Arrow").description("Point towards the waypoint.").defaultValue(true).build();
	private final BoolSetting coords = sgGeneral.bool("Coordinates").build();

	public NearestWaypointHud(WaypointStore store) {
		super("Nearest Waypoint", "", HudStyle.Mode.ACCENT);
		this.store = store;
	}

	@Override
	protected void lines(Lines out) {
		// No world (e.g. arranging the HUD from the title screen): show sample content so the element can be placed.
		if (preview()) {
			out.add("Home", "120m" + (arrow.get() ? " ↗" : ""));
			return;
		}
		Vec3 eye = mc.player.getEyePosition();
		Optional<Waypoint> nearest = store.nearest(eye);
		if (nearest.isEmpty()) {
			out.value("No waypoints");
			return;
		}
		Waypoint w = nearest.get();
		String value = Format.distance(w.center().distanceTo(eye));
		if (arrow.get()) value += " " + arrowTo(w.center(), eye);
		out.add(w.name(), value);
		if (coords.get()) out.value(w.coords());
	}

	private String arrowTo(Vec3 target, Vec3 eye) {
		double angle = Math.toDegrees(Math.atan2(target.z - eye.z, target.x - eye.x)) - 90;
		float relative = Mth.wrapDegrees((float) angle - mc.player.getYRot());
		return ARROWS[Math.floorMod(Math.round(relative / 45f), 8)];
	}
}
