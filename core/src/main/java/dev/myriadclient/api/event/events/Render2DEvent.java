package dev.myriadclient.api.event.events;

import dev.myriadclient.api.ui.draw.Canvas;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jetbrains.annotations.ApiStatus;

/**
 * Posted on the render thread every frame the HUD is drawn, after the vanilla HUD (right after the tab list) and
 * before Myriad's own HUD elements, in scaled GUI coordinates. Draw with {@link #canvas()} (Myriad's renderer, one
 * unit = one scaled GUI pixel) or with the vanilla {@link #context()}; call {@code canvas().flush()} before switching
 * from the canvas to the vanilla context. Only while something listens. Not cancellable.
 */
public final class Render2DEvent {
	private final GuiGraphicsExtractor context;
	private final Canvas canvas;
	private final float tickDelta;

	/** Core's; only Myriad makes these. */
	@ApiStatus.Internal
	public Render2DEvent(GuiGraphicsExtractor context, Canvas canvas, float tickDelta) {
		this.context = context;
		this.canvas = canvas;
		this.tickDelta = tickDelta;
	}

	/** Vanilla's GUI drawing context for this frame. */
	public GuiGraphicsExtractor context() {
		return context;
	}

	/** Myriad's canvas for this frame, in scaled GUI pixels. */
	public Canvas canvas() {
		return canvas;
	}

	/** How far the frame is between the last tick and the next (0 to 1), for smooth movement. */
	public float tickDelta() {
		return tickDelta;
	}
}
