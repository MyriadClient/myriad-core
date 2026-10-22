package dev.myriadclient.api.render;

import dev.myriadclient.api.Myriad;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * Render types for drawing into the world through a {@code SubmitNodeCollector} (see {@code Render3DEvent.submits()}),
 * for geometry {@link Renderer3D} doesn't cover. All are translucent and don't write depth.
 */
public final class RenderLayers {
	private RenderLayers() {
	}

	/** Lines (position, colour, normal = direction, line width). */
	public static RenderType lines(boolean throughWalls) {
		return Myriad.render().layer(Rendering.Layer.LINES, throughWalls);
	}

	/** Quads in a flat colour (position, colour). */
	public static RenderType quads(boolean throughWalls) {
		return Myriad.render().layer(Rendering.Layer.QUADS, throughWalls);
	}

	/** Entity models in a flat colour: pass to {@code submitModel} with the colour as the tint. */
	public static RenderType flatModel(boolean throughWalls) {
		return Myriad.render().layer(Rendering.Layer.MODEL, throughWalls);
	}
}
