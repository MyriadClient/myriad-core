package dev.myriadclient.api.event.events;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;

/**
 * Where the local player's view ray starts and points, as used for the crosshair target, block reach and placement.
 * Overwrite {@link #value} to aim from somewhere else (e.g. a freecam). Posted whenever the local player's eye position
 * or view vector is asked for (on the render thread, many times a frame), only while something listens, so keep
 * handlers cheap. Not cancellable.
 */
public abstract class PlayerViewEvent {
	/** The eye position or look direction; overwrite it to change what the game uses. */
	public Vec3 value;
	private final float tickDelta;

	/** Core's; addons don't make these. */
	protected PlayerViewEvent(Vec3 value, float tickDelta) {
		this.value = value;
		this.tickDelta = tickDelta;
	}

	/** The partial tick it was asked for (1 for a tick's own logic, the frame's for rendering). */
	public float tickDelta() {
		return tickDelta;
	}

	/** The eye position the view ray starts from. */
	public static final class Eyes extends PlayerViewEvent {
		/** Core's; only Myriad makes these. */
		@ApiStatus.Internal
		public Eyes(Vec3 value, float tickDelta) {
			super(value, tickDelta);
		}
	}

	/** The unit look direction. */
	public static final class Look extends PlayerViewEvent {
		/** Core's; only Myriad makes these. */
		@ApiStatus.Internal
		public Look(Vec3 value, float tickDelta) {
			super(value, tickDelta);
		}
	}
}
