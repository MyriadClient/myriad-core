package dev.myriadclient.api.render;

import dev.myriadclient.api.Myriad;

/**
 * How modules draw what they show in the world, one choice for all of them, the player's (the Render window, or
 * {@code .render}): {@link #SHADER} traces outlines, glow and fills around the exact shape of things; {@link #BOX} draws
 * boxes of lines and faces, lighter on a weak GPU. A module that shows things through {@code RenderEvent} and
 * {@link RenderSettings} never needs to ask: core draws them in the mode that's on, and the module's Render options show
 * the ones for that mode.
 */
public enum RenderMode {
	SHADER, BOX;

	/** The mode that's on. */
	public static RenderMode current() {
		return Myriad.render().mode();
	}
}
