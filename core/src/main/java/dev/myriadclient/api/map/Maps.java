package dev.myriadclient.api.map;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * The map mods installed alongside Myriad, read without depending on them: everything is empty when none is. For now
 * that's Xaero's Minimap's waypoints, for features that take a place by name rather than coordinates:
 *
 * <pre>{@code
 * Myriad.maps().waypoint("Base").map(Myriad.maps()::positionHere).ifPresent(pos -> ...);
 * }</pre>
 */
@ApiStatus.NonExtendable
public interface Maps {
	/** Whether a map mod whose waypoints Myriad reads is installed (Xaero's Minimap). */
	boolean available();

	/**
	 * Every waypoint of the world you're in, in every dimension, as the map has them: this dimension's first. Empty
	 * outside a world, without a map mod, or before it has loaded them. Worked out at most once a tick.
	 */
	List<Waypoint> waypoints();

	/** The first waypoint called {@code name} (case ignored), this dimension's before another's; empty if none is. */
	default Optional<Waypoint> waypoint(String name) {
		for (Waypoint w : waypoints()) if (w.name().equalsIgnoreCase(name)) return Optional.of(w);
		return Optional.empty();
	}

	/**
	 * Where {@code waypoint} is from the dimension you're in: its block's centre, scaled across dimensions (a Nether
	 * waypoint's x and z times 8 in the Overworld), its height unchanged; null outside a world.
	 */
	@Nullable Vec3 positionHere(Waypoint waypoint);
}
