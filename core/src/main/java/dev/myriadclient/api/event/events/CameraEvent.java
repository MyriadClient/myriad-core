package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import org.jetbrains.annotations.ApiStatus;

/** Hooks into the camera, for modules like Freecam and FreeLook. */
public final class CameraEvent {
	private CameraEvent() {
	}

	/** The camera's position this frame; overwrite x/y/z to move it. */
	public static final class Position {
		public double x, y, z;
		private final float tickDelta;

		@ApiStatus.Internal
		public Position(double x, double y, double z, float tickDelta) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.tickDelta = tickDelta;
		}

		public float tickDelta() {
			return tickDelta;
		}
	}

	/** The camera's rotation this frame; overwrite yaw/pitch to look elsewhere without turning the player. */
	public static final class Rotation {
		public float yaw, pitch;
		private final float tickDelta;

		@ApiStatus.Internal
		public Rotation(float yaw, float pitch, float tickDelta) {
			this.yaw = yaw;
			this.pitch = pitch;
			this.tickDelta = tickDelta;
		}

		public float tickDelta() {
			return tickDelta;
		}
	}

	/** Whether the local player's own body is drawn (true in third person). Set it when the camera leaves the body. */
	public static final class Detached {
		public boolean renderSelf;

		@ApiStatus.Internal
		public Detached(boolean renderSelf) {
			this.renderSelf = renderSelf;
		}
	}

	/** About to draw the first-person hand and held item. Cancel to hide them. */
	public static final class Hand extends Cancellable {
	}
}
