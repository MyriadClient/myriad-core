package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import org.jetbrains.annotations.ApiStatus;

/**
 * Hooks into the camera, for modules like Freecam and FreeLook. Each is posted on the render thread every frame, only
 * while something listens. Changing the camera doesn't move or turn the player, nor what it targets
 * ({@link PlayerViewEvent} does that).
 */
public final class CameraEvent {
	private CameraEvent() {
	}

	/** The camera's position this frame, as it follows its entity; overwrite x/y/z to move it. Not cancellable. */
	public static final class Position {
		/** Where the camera is, in world coordinates; overwrite to move it. */
		public double x, y, z;
		private final float tickDelta;

		/** Core's; only Myriad makes these. */
		@ApiStatus.Internal
		public Position(double x, double y, double z, float tickDelta) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.tickDelta = tickDelta;
		}

		/** The partial tick the frame is drawn at. */
		public float tickDelta() {
			return tickDelta;
		}
	}

	/**
	 * The camera's rotation this frame; overwrite yaw/pitch to look elsewhere without turning the player. Not
	 * cancellable.
	 */
	public static final class Rotation {
		/** Which way the camera faces, in degrees (pitch -90 up to 90 down); overwrite to turn it. */
		public float yaw, pitch;
		private final float tickDelta;

		/** Core's; only Myriad makes these. */
		@ApiStatus.Internal
		public Rotation(float yaw, float pitch, float tickDelta) {
			this.yaw = yaw;
			this.pitch = pitch;
			this.tickDelta = tickDelta;
		}

		/** The partial tick the frame is drawn at. */
		public float tickDelta() {
			return tickDelta;
		}
	}

	/** Whether the local player's own body is drawn (true in third person). Set it when the camera leaves the body. */
	public static final class Detached {
		/** Whether to draw your own body this frame; starts as whether the camera is in third person. */
		public boolean renderSelf;

		/** Core's; only Myriad makes these. */
		@ApiStatus.Internal
		public Detached(boolean renderSelf) {
			this.renderSelf = renderSelf;
		}
	}

	/** About to draw the first-person hand and held item. Cancel to hide them. */
	public static final class Hand extends Cancellable {
	}
}
