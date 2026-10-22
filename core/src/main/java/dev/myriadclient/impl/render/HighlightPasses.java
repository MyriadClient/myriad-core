package dev.myriadclient.impl.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.myriadclient.api.render.HighlightStyle;
import dev.myriadclient.api.render.WorldMesh;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntRBTreeSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The highlights' GPU work: retained meshes into the shapes masks, then per layer the resolve and spread passes over
 * its mask and the composite onto the main target, and the data buffer the shaders read the highlights from.
 */
final class HighlightPasses {
	/** vec4s before the first highlight: (radius, GUI scale, count, boundary radius), (seconds, 0, 0, 0). */
	private static final int HEADER = 2;
	private static final Matrix4f IDENTITY = new Matrix4f();

	/** Addons' fills: shader id number to its composite pass. */
	private static final Int2ObjectOpenHashMap<RenderPipeline> FILLS = new Int2ObjectOpenHashMap<>();
	private static final long START = System.nanoTime();

	private HighlightPasses() {
	}

	/** Registers an addon's fill (see {@link HighlightStyle.Fill#custom}): its shader becomes one more composite pass. */
	static void registerFill(int shaderId, Identifier shader) {
		FILLS.put(shaderId, MyriadPipelines.highlightFill(shaderId, shader));
	}

	/** A retained mesh to draw into a shapes mask, with the first id of its palette. */
	record MeshDraw(WorldMesh mesh, int baseId, int layer) {
	}

	/** One mask's passes and results. */
	static final class Layer {
		final String name;
		TextureTarget resolved, spread;
		/** Addons' fills used by this layer's highlights this frame. */
		final IntRBTreeSet customFills = new IntRBTreeSet();
		int count;
		boolean anyDepthTested, prepared;
		int[] area;
		final int[] compositeRect = new int[4];

		Layer(String name) {
			this.name = name;
		}

		void reset() {
			customFills.clear();
			count = 0;
			anyDepthTested = false;
			prepared = false;
			area = null;
		}
	}

	/** Retained meshes into the shapes masks: their quads, with each vertex's palette index added to the base id. */
	static void drawMeshes(CommandEncoder encoder, GpuBuffer gpuData, List<MeshDraw> meshes, TextureTarget[] masks, boolean[] masked,
						   HighlightTable at) {
		// Inside the level pass: the model-view matrix is the camera's view, as for MeshRenderer.
		Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
		int maxIndices = 0;
		for (MeshDraw d : meshes) maxIndices = Math.max(maxIndices, Math.max(d.mesh().indexCount(0), d.mesh().indexCount(2)));
		if (maxIndices == 0) return;
		RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		GpuBuffer indexBuffer = indices.getBuffer(maxIndices);
		GpuBufferSlice[] transforms = new GpuBufferSlice[meshes.size()];
		for (int i = 0; i < meshes.size(); i++) {
			MeshDraw d = meshes.get(i);
			WorldMesh m = d.mesh();
			// The base id rides in the colour modulator, the same bytes as an encoded outline colour.
			Vector4f base = new Vector4f((d.baseId() & 0xFF) / 255f, (d.baseId() >> 8 & 0xFF) / 255f, 0, 1);
			Vector3f offset = new Vector3f((float) (m.originX() - at.camX), (float) (m.originY() - at.camY), (float) (m.originZ() - at.camZ));
			transforms[i] = RenderSystem.getDynamicUniforms().writeTransform(modelView, base, offset, IDENTITY);
		}
		for (int layer : HighlightRenderer.SHAPE_LAYERS) {
			if (!masked[layer]) continue;
			drawMeshes(encoder, gpuData, meshes, masks[layer], layer, transforms, indexBuffer, indices.type());
		}
	}

	private static void drawMeshes(CommandEncoder encoder, GpuBuffer gpuData, List<MeshDraw> meshes, TextureTarget mask, int layer,
								   GpuBufferSlice[] transforms, GpuBuffer indexBuffer, IndexType indexType) {
		boolean any = false;
		for (MeshDraw d : meshes) any |= d.layer() == layer;
		if (!any) return;
		try (RenderPass pass = encoder.createRenderPass(() -> "Myriad highlight meshes", mask.getColorTextureView(), Optional.empty(),
			mask.getDepthTextureView(), OptionalDouble.empty())) {
			pass.setPipeline(MyriadPipelines.HIGHLIGHT_MESH);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("Highlights", gpuData);
			pass.setIndexBuffer(indexBuffer, indexType);
			for (int i = 0; i < meshes.size(); i++) {
				if (meshes.get(i).layer() != layer) continue;
				WorldMesh m = meshes.get(i).mesh();
				pass.setUniform("DynamicTransforms", transforms[i]);
				for (int part : new int[]{0, 2}) {
					GpuBuffer vertices = m.buffer(part);
					int n = m.indexCount(part);
					if (vertices == null || n == 0) continue;
					pass.setVertexBuffer(0, vertices.slice());
					pass.drawIndexed(n, 1, 0, 0, 0);
				}
			}
		}
	}

	/** Resolves {@code layer}'s hidden pixels (if any of its highlights isn't through walls) and runs the row pass. */
	static void prepare(CommandEncoder encoder, Layer layer, RenderTarget mask, RenderTarget main, GpuBuffer gpuData, int radius, int w, int h) {
		if (mask == null || mask.getColorTextureView() == null || layer.area == null) return;
		resize(layer, w, h);
		GpuSampler nearestSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		GpuTextureView source = mask.getColorTextureView();
		int[] area = layer.area;
		if (layer.anyDepthTested) {
			try (RenderPass pass = begin(encoder, MyriadPipelines.HIGHLIGHT_RESOLVE, layer.resolved.getColorTextureView(), gpuData, grow(area, 2 * radius, 2 * radius, w, h))) {
				pass.bindTexture("Mask", source, nearestSampler);
				pass.bindTexture("MaskDepth", mask.getDepthTextureView(), nearestSampler);
				pass.bindTexture("SceneDepth", main.getDepthTextureView(), nearestSampler);
				pass.draw(3, 1, 0, 0);
			}
			source = layer.resolved.getColorTextureView();
		}
		try (RenderPass pass = begin(encoder, MyriadPipelines.HIGHLIGHT_SPREAD, layer.spread.getColorTextureView(), gpuData, grow(area, radius, 2 * radius, w, h))) {
			pass.bindTexture("InSampler", source, nearestSampler);
			pass.draw(3, 1, 0, 0);
		}
		System.arraycopy(grow(area, radius, radius, w, h), 0, layer.compositeRect, 0, 4);
		layer.prepared = true;
	}

	/** One layer onto the main target: outlines, glows and built-in fills, then addons' fills over their highlights' insides. */
	static void composite(CommandEncoder encoder, Layer layer, RenderTarget main, GpuBuffer gpuData) {
		composite(encoder, MyriadPipelines.HIGHLIGHT_COMPOSITE, layer, main, gpuData);
		for (int fill : layer.customFills) {
			RenderPipeline pipeline = FILLS.get(fill);
			if (pipeline != null) composite(encoder, pipeline, layer, main, gpuData);
		}
	}

	private static void composite(CommandEncoder encoder, RenderPipeline pipeline, Layer layer, RenderTarget main, GpuBuffer gpuData) {
		try (RenderPass pass = begin(encoder, pipeline, main.getColorTextureView(), gpuData, layer.compositeRect)) {
			pass.bindTexture("SpreadSampler", layer.spread.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}
	}

	private static RenderPass begin(CommandEncoder encoder, RenderPipeline pipeline, GpuTextureView target, GpuBuffer gpuData, int[] rect) {
		RenderPass pass = encoder.createRenderPass(() -> "Myriad highlights", target, Optional.empty(), null, OptionalDouble.empty());
		pass.setPipeline(pipeline);
		RenderSystem.bindDefaultUniforms(pass);
		pass.setUniform("Highlights", gpuData);
		pass.enableScissor(rect[0], rect[1], rect[2], rect[3]);
		return pass;
	}

	/** {@code area} grown by {@code dx}, {@code dy} and clamped to the screen, as a scissor {x, y, width, height}. */
	private static int[] grow(int[] area, int dx, int dy, int w, int h) {
		int x0 = Math.max(0, area[0] - dx), y0 = Math.max(0, area[1] - dy);
		int x1 = Math.min(w, area[2] + dx), y1 = Math.min(h, area[3] + dy);
		return new int[]{x0, y0, x1 - x0, y1 - y0};
	}

	/** {@code ring} moved on to this frame's slot, or a new one if it can't hold {@code highlights}. */
	static MappableRingBuffer ring(MappableRingBuffer ring, String name, int highlights) {
		int bytes = (HEADER + highlights * HighlightTable.TEXELS) * 16;
		if (ring != null && ring.size() >= bytes) {
			ring.rotate();
			return ring;
		}
		if (ring != null) GpuGarbage.close(ring);
		int size = Math.max(4096, Integer.highestOneBit(bytes - 1) << 1);
		return new MappableRingBuffer(() -> name, GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, size);
	}

	/** Writes the header and highlights {@code from} to {@code to} (as ids from 0) to {@code ring}'s current slot. */
	static GpuBuffer write(MappableRingBuffer ring, int radius, int boundary, float[] data, int from, int to) {
		GpuBuffer gpu = ring.currentBuffer();
		try (GpuBufferSlice.MappedView view = gpu.map(false, true)) {
			FloatBuffer out = view.data().order(ByteOrder.nativeOrder()).asFloatBuffer();
			out.put(radius).put((float) Minecraft.getInstance().getWindow().getGuiScale()).put(to - from).put(boundary);
			out.put((float) ((System.nanoTime() - START) / 1e9 % 3600)).put(0).put(0).put(0);
			out.put(data, from * HighlightTable.FLOATS, (to - from) * HighlightTable.FLOATS);
		}
		return gpu;
	}

	private static void resize(Layer layer, int w, int h) {
		if (layer.spread == null) {
			layer.resolved = new TextureTarget("Myriad highlights resolved (" + layer.name + ")", w, h, false, GpuFormat.RGBA8_UNORM);
			layer.spread = new TextureTarget("Myriad highlights spread (" + layer.name + ")", w, h, false, GpuFormat.RGBA16_UNORM);
		} else if (layer.spread.width != w || layer.spread.height != h) {
			layer.resolved.resize(w, h);
			layer.spread.resize(w, h);
		}
	}
}
