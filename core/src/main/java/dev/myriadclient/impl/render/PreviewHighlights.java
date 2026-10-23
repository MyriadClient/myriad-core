package dev.myriadclient.impl.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.myriadclient.api.render.HighlightStyle;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;

/**
 * A preview's highlights: the shader outlines, glows and fills of {@link HighlightRenderer}, drawn into a preview's own
 * texture. Its subject is drawn with a highlight's id as its outline colour ({@link #add}), its outline pass goes into
 * this mask while it's drawn ({@link #drawing}), and {@link #finish} spreads and composites it onto the texture. The
 * whole texture is one area; nothing hides the subject, so nothing is resolved against a scene.
 */
public final class PreviewHighlights {
	/** The preview whose outline pass is running now (LevelRendererMixin points the outline target at its mask). */
	private static @Nullable PreviewHighlights drawing;
	private static final AABB HERE = new AABB(0, 0, 0, 0, 0, 0);

	private final HighlightTable table = new HighlightTable();
	private final HighlightPasses.Layer layer = new HighlightPasses.Layer("preview");
	private @Nullable TextureTarget mask;
	private @Nullable MappableRingBuffer buffer;
	private int width, height;

	/** Starts a frame of a texture {@code width} by {@code height}. */
	public void begin(int width, int height) {
		table.clear();
		layer.reset();
		this.width = width;
		this.height = height;
	}

	/** The outline colour to draw something highlighted with {@code style} and {@code color} in; 0 for no highlight. */
	public int add(@Nullable HighlightStyle style, int color) {
		if (style == null || (color >>> 24) == 0) return 0;
		if (table.count == 0) clearMask();
		int id = table.add(0, style, color, HERE, HERE);
		if (id < 0) return 0;
		layer.count++;
		if (style.fill().isCustom()) layer.customFills.add(style.fill().shaderId());
		return HighlightRenderer.encode(id, table.groupOf[id]);
	}

	private void clearMask() {
		if (mask == null) mask = new TextureTarget("Myriad preview highlights", width, height, true, GpuFormat.RGBA8_UNORM);
		else if (mask.width != width || mask.height != height) mask.resize(width, height);
		RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(mask.getColorTexture(), new Vector4f(0), mask.getDepthTexture(), 0.0);
	}

	/** Whether anything in this frame is highlighted. */
	public boolean any() {
		return table.count > 0;
	}

	/** Its outline pass is about to run ({@code true}) or is over. */
	public void drawing(boolean on) {
		drawing = on && any() ? this : null;
	}

	/** The preview whose outline pass is running, if any. */
	public static @Nullable PreviewHighlights drawing() {
		return drawing;
	}

	public @Nullable RenderTarget mask() {
		return mask;
	}

	/** Spreads the silhouettes and draws the highlights onto {@code target}, the preview's texture. */
	public void finish(@Nullable GpuTextureView target) {
		if (!any() || target == null || mask == null) return;
		float[] widths = table.fillData(0, table.count);
		// The gradient spans the whole texture.
		for (int id = 0; id < table.count; id++) {
			int i = id * HighlightTable.FLOATS + 8;
			table.data[i] = 0;
			table.data[i + 1] = 0;
			table.data[i + 2] = width;
			table.data[i + 3] = height;
		}
		int radius = Math.min(64, (int) Math.ceil(widths[0]) + 1);
		int boundary = Math.min(radius, (int) Math.ceil(widths[1]) + 1);
		buffer = HighlightPasses.ring(buffer, "Myriad preview highlights", table.count);
		GpuBuffer gpuData = HighlightPasses.write(buffer, radius, boundary, table.data, 0, table.count);
		layer.area = new int[]{0, 0, width, height};
		layer.anyDepthTested = false;
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		HighlightPasses.prepare(encoder, layer, mask, null, gpuData, radius, width, height);
		if (!layer.prepared) return;
		layer.prepared = false;
		HighlightPasses.composite(encoder, layer, target, gpuData);
	}
}
