package dev.myriadclient.api.event.events;

import dev.myriadclient.api.render.Canvas;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jetbrains.annotations.ApiStatus;

/**
 * Fired after the vanilla HUD renders, in scaled GUI coordinates. Draw with {@link #canvas()} (Myriad's renderer,
 * one unit = one scaled GUI pixel) or with the vanilla {@link #context()}; call {@code canvas().flush()} before
 * switching from the canvas to the vanilla context.
 */
public final class Render2DEvent {
	private final GuiGraphicsExtractor context;
	private final Canvas canvas;
	private final float tickDelta;

	@ApiStatus.Internal
	public Render2DEvent(GuiGraphicsExtractor context, Canvas canvas, float tickDelta) {
		this.context = context;
		this.canvas = canvas;
		this.tickDelta = tickDelta;
	}

	public GuiGraphicsExtractor context() {
		return context;
	}

	public Canvas canvas() {
		return canvas;
	}

	public float tickDelta() {
		return tickDelta;
	}
}
