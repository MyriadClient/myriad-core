package dev.myriadclient.api.map;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A waypoint from a map mod: its name, where it is in its own dimension, and that dimension's coordinate scale (8 in
 * the Nether, 1 in the Overworld and the End), so it can be found from another dimension. {@code set} is the map's
 * waypoint set it's in, {@code source} the map mod ("Xaero's Minimap").
 */
public record Waypoint(String name, int x, int y, int z, ResourceKey<Level> dimension, double scale, String set, String source) {
	/** Where it is from a dimension of coordinate scale {@code scale}: its block's centre, scaled across, at its height. */
	public Vec3 positionAt(double scale) {
		double f = this.scale / scale;
		return new Vec3((x + 0.5) * f, y, (z + 0.5) * f);
	}
}
