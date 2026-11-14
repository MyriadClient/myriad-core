package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import org.jetbrains.annotations.ApiStatus;

/**
 * The mouse wheel (or a touchpad) scrolled in the game window, in a screen or not. Posted on the render thread before
 * Minecraft handles it. Cancel it to hide the scroll from Minecraft: no hotbar slot change, no screen scrolling.
 */
public final class MouseScrollEvent extends Cancellable {
	private final double horizontal, vertical;

	/** Core's; only Myriad makes these. */
	@ApiStatus.Internal
	public MouseScrollEvent(double horizontal, double vertical) {
		this.horizontal = horizontal;
		this.vertical = vertical;
	}

	/** Horizontal scroll, in wheel steps (positive is right; touchpads give fractions). */
	public double horizontal() {
		return horizontal;
	}

	/** Vertical scroll, in wheel steps (positive is up, away from you; touchpads give fractions). */
	public double vertical() {
		return vertical;
	}
}
