package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import org.jetbrains.annotations.ApiStatus;

/**
 * Mouse movement is about to turn the player. Cancel to keep the player still (and use the deltas yourself), or
 * change the deltas with {@link #set} to scale or redirect the turn (Zoom slows it down). Posted on the render thread,
 * every frame the mouse moves the camera, only while something listens; sensitivity and inverted mouse are already
 * applied.
 */
public final class MouseLookEvent extends Cancellable {
	private double deltaX, deltaY;

	/** Core's; only Myriad makes these. */
	@ApiStatus.Internal
	public MouseLookEvent(double deltaX, double deltaY) {
		this.deltaX = deltaX;
		this.deltaY = deltaY;
	}

	/** Horizontal look delta in vanilla units (multiply by 0.15 for degrees). */
	public double deltaX() {
		return deltaX;
	}

	/** Vertical look delta in vanilla units (multiply by 0.15 for degrees; positive turns down). */
	public double deltaY() {
		return deltaY;
	}

	/** Turns by these deltas instead (same units as {@link #deltaX()} and {@link #deltaY()}). */
	public void set(double deltaX, double deltaY) {
		this.deltaX = deltaX;
		this.deltaY = deltaY;
	}
}
