package dev.myriadclient.impl.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * A run of UI quads recorded by {@link UiRenderer}, handed to vanilla's GUI renderer as one element so it is drawn in
 * order with vanilla's own items and text (the GUI layers elements by where they overlap).
 * <p>
 * Vanilla's vertex builder only knows its own attributes, so after it writes Position this writes the rest of
 * {@link MyriadPipelines#UI_FORMAT} straight into the vertex.
 */
final class UiBatch implements GuiElementRenderState {
	/** Floats per vertex after the position (x, y), matching UI_FORMAT's order. */
	static final int EXTRA_FLOATS = 2 + 2 + 4 + 4 + 4 + 4 + 2 + 4;
	static final int FLOATS_PER_VERTEX = 2 + EXTRA_FLOATS;
	private static final long EXTRA_OFFSET = offsetOf("Local");

	private final float[] data;
	private final int vertices;
	private final TextureSetup textures;
	private final ScreenRectangle bounds;

	UiBatch(float[] data, int vertices, TextureSetup textures, ScreenRectangle bounds) {
		this.data = data;
		this.vertices = vertices;
		this.textures = textures;
		this.bounds = bounds;
	}

	@Override
	public void buildVertices(VertexConsumer consumer) {
		BufferBuilder builder = (BufferBuilder) consumer;
		for (int v = 0, i = 0; v < vertices; v++) {
			builder.addVertex(data[i], data[i + 1], 0f);
			long p = builder.vertexPointer + EXTRA_OFFSET;
			i += 2;
			for (int k = 0; k < EXTRA_FLOATS; k++) MemoryUtil.memPutFloat(p + k * 4L, data[i++]);
		}
	}

	@Override
	public RenderPipeline pipeline() {
		return MyriadPipelines.UI;
	}

	@Override
	public TextureSetup textureSetup() {
		return textures;
	}

	@Override
	public @Nullable ScreenRectangle scissorArea() {
		// Clipping is per vertex, in the shader, so it stays pixel-exact at any GUI scale.
		return null;
	}

	@Override
	public ScreenRectangle bounds() {
		return bounds;
	}

	private static long offsetOf(String attribute) {
		VertexFormat format = MyriadPipelines.UI_FORMAT;
		VertexFormatElement e = format.getElement(attribute);
		if (e == null) throw new IllegalStateException("UI format has no " + attribute);
		return e.offset();
	}
}
