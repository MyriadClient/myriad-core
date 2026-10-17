package dev.myriadclient.api.event.events;

import org.jetbrains.annotations.ApiStatus;
/**
 * Fired before the player sends its movement packets each tick. Handlers may rewrite the values that are sent;
 * the client-side player position/rotation is left untouched. Prefer {@code Myriad.rotations()} over writing
 * yaw/pitch here directly so modules don't fight over rotations.
 */
public final class MovementPacketsEvent {
	public double x, y, z;
	public float yaw, pitch;
	public boolean onGround;

	@ApiStatus.Internal
	public MovementPacketsEvent(double x, double y, double z, float yaw, float pitch, boolean onGround) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.yaw = yaw;
		this.pitch = pitch;
		this.onGround = onGround;
	}

	/** Fired after movement packets were sent. */
	public static final class Post {
		public static final Post INSTANCE = new Post();
	}
}
