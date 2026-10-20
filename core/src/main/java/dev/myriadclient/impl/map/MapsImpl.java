package dev.myriadclient.impl.map;

import dev.myriadclient.api.map.Maps;
import dev.myriadclient.api.map.Waypoint;
import dev.myriadclient.api.util.TickCached;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Waypoints from Xaero's Minimap when it's installed. Its classes are only touched through {@link XaeroWaypoints},
 * after checking the mod is loaded, so core runs without it; a change on its side that breaks the reading turns it off
 * with a log line.
 */
public final class MapsImpl implements Maps {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Maps");
	private static final boolean XAERO = FabricLoader.getInstance().isModLoaded("xaerominimap");

	private boolean broken;
	private final TickCached<List<Waypoint>> waypoints = TickCached.of(this::read);

	@Override
	public boolean available() {
		return XAERO && !broken;
	}

	@Override
	public List<Waypoint> waypoints() {
		return waypoints.get();
	}

	@Override
	public @Nullable Vec3 positionHere(Waypoint waypoint) {
		var level = Minecraft.getInstance().level;
		return level == null ? null : waypoint.positionAt(level.dimensionType().coordinateScale());
	}

	private List<Waypoint> read() {
		var level = Minecraft.getInstance().level;
		if (!available() || level == null) return List.of();
		List<Waypoint> all;
		try {
			all = XaeroWaypoints.read();
		} catch (LinkageError | RuntimeException e) {
			broken = true;
			LOG.warn("Couldn't read Xaero's Minimap's waypoints; off until restart", e);
			return List.of();
		}
		// This dimension's first, so a name both have finds the one here.
		List<Waypoint> sorted = new ArrayList<>(all.size());
		for (Waypoint w : all) if (w.dimension().equals(level.dimension())) sorted.add(w);
		for (Waypoint w : all) if (!w.dimension().equals(level.dimension())) sorted.add(w);
		return List.copyOf(sorted);
	}
}
