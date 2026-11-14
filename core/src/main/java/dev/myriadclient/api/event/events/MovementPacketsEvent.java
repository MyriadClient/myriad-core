package dev.myriadclient.api.event.events;

import org.jetbrains.annotations.ApiStatus;

/**
 * Posted on the render thread just before the player sends its movement packet each tick (at the end of its tick),
 * with the values about to go out. Handlers may rewrite them; the client-side player's position and rotation are left
 * untouched. Prefer {@code Myriad.rotations()} over writing yaw and pitch here directly so modules don't fight over
 * rotations (core's rotations write them at {@code Priority.LOW}). Vanilla still decides whether a packet goes out and
 * which kind, from the values here. Not cancellable.
 */
public final class MovementPacketsEvent {
	/** The position to send, in world coordinates (the player's feet). */
	public double x, y, z;
	/** The rotation to send, in degrees (yaw unwrapped, pitch -90 up to 90 down). */
	public float yaw, pitch;
	/** Whether to tell the server you're on the ground (fall damage is worked out from it). */
	public boolean onGround;

	/** Core's; only Myriad makes these. */
	@ApiStatus.Internal
	public MovementPacketsEvent(double x, double y, double z, float yaw, float pitch, boolean onGround) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.yaw = yaw;
		this.pitch = pitch;
		this.onGround = onGround;
	}

	/** Posted on the render thread right after the movement packet was sent (or vanilla decided none was needed). */
	public static final class Post {
		/** The one instance, posted every tick. */
		public static final Post INSTANCE = new Post();
	}
}
