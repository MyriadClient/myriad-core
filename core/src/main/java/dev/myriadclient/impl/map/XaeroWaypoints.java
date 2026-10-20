package dev.myriadclient.impl.map;

import dev.myriadclient.api.map.Waypoint;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;
import xaero.hud.minimap.world.container.MinimapWorldRootContainer;

import java.util.ArrayList;
import java.util.List;

/** Xaero's Minimap's waypoints for the server you're on, every dimension. Only loaded when the minimap is installed. */
final class XaeroWaypoints {
	private static final String SOURCE = "Xaero's Minimap";

	private XaeroWaypoints() {
	}

	static List<Waypoint> read() {
		MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
		if (session == null) return List.of();
		MinimapWorldRootContainer root = session.getWorldManager().getCurrentRootContainer();
		if (root == null) return List.of();
		List<Waypoint> out = new ArrayList<>();
		for (MinimapWorld world : root.getAllWorldsIterable()) {
			ResourceKey<Level> dim = world.getDimId();
			if (dim == null) continue;
			double scale = root.getDimensionScale(dim);
			for (WaypointSet set : world.getIterableWaypointSets()) {
				for (var w : set.getWaypoints()) out.add(new Waypoint(w.getLocalizedName(), w.getX(), w.getY(), w.getZ(), dim, scale, set.getName(), SOURCE));
			}
		}
		return out;
	}
}
