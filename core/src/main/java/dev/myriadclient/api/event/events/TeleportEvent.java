package dev.myriadclient.api.event.events;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;

/**
 * The server is setting your position: a teleport, or a rubberband when it didn't accept where you moved. Posted on
 * the render thread just before the new position is applied (the client then confirms it to the server). Movement
 * tricks back off here; {@code Myriad.server().rubberbandedWithin(ms)} answers the same question later.
 */
public final class TeleportEvent {
	private final Vec3 from, to;

	@ApiStatus.Internal
	public TeleportEvent(Vec3 from, Vec3 to) {
		this.from = from;
		this.to = to;
	}

	/** Where you were. */
	public Vec3 from() {
		return from;
	}

	/** Where the server puts you. */
	public Vec3 to() {
		return to;
	}

	/** How far you're moved. */
	public double distance() {
		return from.distanceTo(to);
	}
}
