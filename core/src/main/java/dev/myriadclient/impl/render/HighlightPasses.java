package dev.myriadclient.impl.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.myriadclient.api.render.HighlightStyle;
import dev.myriadclient.api.render.WorldMesh;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntRBTreeSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
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
	/**
	 * vec4s before the first highlight: (radius, GUI scale, count, boundary radius), (seconds, 0, glows, glow scale),
	 * (1 if the masks' depth can be read, 1 if depth runs 0..1 in clip space rather than -1..1, 0, 0), the four columns
	 * of the matrix from clip space to view space, then the four of the one from view space to the world's axes (see
	 * highlight.glsl).
	 */
	private static final int HEADER = 11;

	/**
	 * Framebuffer pixels per texel of the glow's distance field for this radius: 2, or 4 once the glow is wide enough
	 * that its falloff hides a coarser field (a sixteenth of the pixels, searching a quarter as far).
	 */
	static int glowScale(int radius) {
		return radius > 16 ? 4 : 2;
	}
	private static final Matrix4f IDENTITY = new Matrix4f();

	/** Addons' fills: shader id number to its composite pass. */
	private static final Int2ObjectOpenHashMap<RenderPipeline> FILLS = new Int2ObjectOpenHashMap<>();
	/** Fills whose shader didn't compile, said once each until they do. */
	private static final IntOpenHashSet BROKEN_FILLS = new IntOpenHashSet();
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Highlights");
	private static final long START = System.nanoTime();
	private static @Nullable TextureTarget blank, sceneCopy;

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
		/** The glow at half size: the row search, then the distance field. */
		TextureTarget glowRow, glow;
		/** Addons' fills used by this layer's highlights this frame. */
		final IntRBTreeSet customFills = new IntRBTreeSet();
		int count;
		boolean anyDepthTested, prepared;
		int[] area;
		/** The mask's depth, for addons' fills (null: none). */
		@Nullable GpuTextureView depth;
		/** The scene's depth where the layer is composited, for fills reading the scene (null: none, as in a preview). */
		@Nullable GpuTextureView sceneDepth;
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

	/** {@code mask} (made the first time, resized to {@code width} by {@code height}) cleared for a frame's silhouettes. */
	static TextureTarget clearedMask(@Nullable TextureTarget mask, String label, int width, int height) {
		if (mask == null) mask = new TextureTarget(label, width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
		else if (mask.width != width || mask.height != height) mask.resize(width, height);
		RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(mask.getColorTexture(), new Vector4f(0), mask.getDepthTexture(), 0.0);
		return mask;
	}

	/**
	 * A frame's boxes ({@code mesh}, camera-relative) into {@code mask}, with {@code vertices} kept from the last frame's
	 * if it's big enough. Returns the buffer they're in.
	 */
	static GpuBuffer drawShapes(CommandEncoder encoder, TextureTarget mask, MeshData mesh, @Nullable GpuBuffer vertices) {
		ByteBuffer data = mesh.vertexBuffer();
		if (vertices == null || vertices.size() < data.remaining()) {
			if (vertices != null) vertices.close();
			int size = Math.max(data.remaining() * 2, 1 << 16);
			vertices = RenderSystem.getDevice().createBuffer(() -> "Myriad highlight shapes", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, size);
		}
		encoder.writeToBuffer(vertices.slice(0, data.remaining()), data);
		int count = mesh.drawState().indexCount();
		RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		GpuBuffer indexBuffer = indices.getBuffer(count);
		GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy(), new Vector4f(1, 1, 1, 1), new Vector3f(), IDENTITY);
		try (RenderPass pass = encoder.createRenderPass(() -> "Myriad highlight shapes", mask.getColorTextureView(), Optional.empty(),
			mask.getDepthTextureView(), OptionalDouble.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(MyriadPipelines.HIGHLIGHT_SHAPES_PIPELINE));
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", transform);
			pass.setIndexBuffer(indexBuffer, indices.type());
			pass.setVertexBuffer(0, vertices.slice());
			pass.drawIndexed(count, 1, 0, 0, 0);
		}
		return vertices;
	}

	private static void drawMeshes(CommandEncoder encoder, GpuBuffer gpuData, List<MeshDraw> meshes, TextureTarget mask, int layer,
								   GpuBufferSlice[] transforms, GpuBuffer indexBuffer, IndexType indexType) {
		boolean any = false;
		for (MeshDraw d : meshes) any |= d.layer() == layer;
		if (!any) return;
		try (RenderPass pass = encoder.createRenderPass(() -> "Myriad highlight meshes", mask.getColorTextureView(), Optional.empty(),
			mask.getDepthTextureView(), OptionalDouble.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(MyriadPipelines.HIGHLIGHT_MESH));
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

	/**
	 * Resolves {@code layer}'s hidden pixels (if any of its highlights isn't through walls; {@code main} gives the scene's
	 * depth, and may be null otherwise) and runs the row pass.
	 */
	static void prepare(CommandEncoder encoder, Layer layer, RenderTarget mask, @Nullable RenderTarget main, GpuBuffer gpuData, int radius, int boundary,
						int w, int h) {
		if (mask == null || mask.getColorTextureView() == null || layer.area == null) return;
		resize(layer, w, h);
		layer.depth = mask.getDepthTextureView();
		layer.sceneDepth = main != null ? main.getDepthTextureView() : null;
		GpuSampler nearestSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		GpuTextureView source = mask.getColorTextureView();
		int[] area = layer.area;
		if (layer.anyDepthTested && main != null) {
			// Far enough out for the spread's search and the glow's.
			int out = 2 * radius + 4;
			try (RenderPass pass = begin(encoder, MyriadPipelines.HIGHLIGHT_RESOLVE, layer.resolved.getColorTextureView(), gpuData, grow(area, out, out, w, h))) {
				pass.setUniform("Mask", source, nearestSampler);
				pass.setUniform("MaskDepth", mask.getDepthTextureView(), nearestSampler);
				pass.setUniform("SceneDepth", main.getDepthTextureView(), nearestSampler);
				pass.draw(3, 1, 0, 0);
			}
			source = layer.resolved.getColorTextureView();
		}
		GpuTimings.begin(GpuTimings.Pass.SPREAD);
		try (RenderPass pass = begin(encoder, MyriadPipelines.HIGHLIGHT_SPREAD, layer.spread.getColorTextureView(), gpuData, grow(area, radius, 2 * radius, w, h))) {
			pass.setUniform("InSampler", source, nearestSampler);
			pass.draw(3, 1, 0, 0);
		}
		GpuTimings.end(GpuTimings.Pass.SPREAD);
		if (radius > boundary) {
			GpuTimings.begin(GpuTimings.Pass.GLOW);
			glow(encoder, layer, source, gpuData, radius, w, h);
			GpuTimings.end(GpuTimings.Pass.GLOW);
		}
		System.arraycopy(grow(area, radius, radius, w, h), 0, layer.compositeRect, 0, 4);
		layer.prepared = true;
	}

	/**
	 * The glow's distance field, at half size or smaller ({@link #glowScale}): a glow is soft, so where it's within a pixel
	 * or two of exact doesn't show, and a quarter of the pixels searching half as far is a small part of what the
	 * full-size search to the glow's reach cost.
	 * The outline stays exact (the spread pass, to the outline's reach). Two passes: each one has a cost of its own
	 * however little it draws, which on a fast GPU outweighs the pixels it saves. Each step's area covers what the next
	 * reads.
	 */
	private static void glow(CommandEncoder encoder, Layer layer, GpuTextureView source, GpuBuffer gpuData, int radius, int w, int h) {
		int scale = glowScale(radius);
		int hw = (w + scale - 1) / scale, hh = (h + scale - 1) / scale;
		int[] area = layer.area;
		int[] half = {area[0] / scale, area[1] / scale, (area[2] + scale - 1) / scale, (area[3] + scale - 1) / scale};
		int reach = radius / scale + 1;
		try (RenderPass pass = begin(encoder, MyriadPipelines.HIGHLIGHT_GLOW_ROW, layer.glowRow.getColorTextureView(), gpuData, grow(half, reach + 1, 2 * reach + 2, hw, hh))) {
			// Linear: one read between four pixels sees them all.
			pass.setUniform("InSampler", source, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.draw(3, 1, 0, 0);
		}
		try (RenderPass pass = begin(encoder, MyriadPipelines.HIGHLIGHT_GLOW_COLUMN, layer.glow.getColorTextureView(), gpuData, grow(half, reach + 1, reach + 1, hw, hh))) {
			pass.setUniform("InSampler", layer.glowRow.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}
	}

	/** One layer onto the main target: outlines, glows and built-in fills, then addons' fills over their highlights' insides. */
	static void composite(CommandEncoder encoder, Layer layer, RenderTarget main, GpuBuffer gpuData) {
		composite(encoder, layer, main.getColorTextureView(), gpuData);
	}

	/** One layer onto {@code target} (a preview's texture, say), as {@link #composite(CommandEncoder, Layer, RenderTarget, GpuBuffer)}. */
	static void composite(CommandEncoder encoder, Layer layer, GpuTextureView target, GpuBuffer gpuData) {
		composite(encoder, MyriadPipelines.HIGHLIGHT_COMPOSITE, layer, target, gpuData, null, null);
		for (int fill : layer.customFills) {
			RenderPipeline pipeline = FILLS.get(fill);
			if (pipeline == null || !compiles(fill, pipeline)) continue;
			// A fill reading the scene gets a copy of what's under it, as it is before its own pass.
			GpuTextureView scene = readsScene(fill) ? copyScene(encoder, layer, target, gpuData) : null;
			composite(encoder, pipeline, layer, target, gpuData, FillOptions.buffer(encoder, fill), scene);
		}
	}

	/** Resources reloaded: fills that didn't compile get another go. */
	static void fillsReloaded() {
		BROKEN_FILLS.clear();
	}

	/**
	 * Whether fill {@code shaderId}'s pipeline compiled: compiled now if it hasn't been. A pipeline that doesn't compile
	 * isn't kept, so it's only tried once until the next resource reload ({@link #fillsReloaded}), said once in the log
	 * and drawn as no fill, rather than stopping the game's resources loading as one of its own pipelines would.
	 */
	private static boolean compiles(int shaderId, RenderPipeline pipeline) {
		if (BROKEN_FILLS.contains(shaderId)) return false;
		if (RenderSystem.getCompiledPipelineNullable(pipeline) != null) return true;
		if (BROKEN_FILLS.add(shaderId)) {
			LOG.error("The fill shader {} didn't compile (its errors are above); its highlights are drawn with no fill",
				pipeline.getLocation());
		}
		return false;
	}

	private static boolean readsScene(int shaderId) {
		for (HighlightStyle.Fill f : HighlightStyle.Fill.all()) if (f.shaderId() == shaderId) return f.readsScene();
		return false;
	}

	/** {@code target} copied where the layer is composited, drawn rather than copied (a preview's picture can't be). */
	private static GpuTextureView copyScene(CommandEncoder encoder, Layer layer, GpuTextureView target, GpuBuffer gpuData) {
		int w = target.texture().getWidth(0), h = target.texture().getHeight(0);
		if (sceneCopy == null) sceneCopy = new TextureTarget("Myriad highlights scene", w, h, GpuFormat.RGBA8_UNORM, null);
		else if (sceneCopy.width != w || sceneCopy.height != h) sceneCopy.resize(w, h);
		try (RenderPass pass = begin(encoder, MyriadPipelines.HIGHLIGHT_SCENE_COPY, sceneCopy.getColorTextureView(), gpuData, layer.compositeRect)) {
			pass.setUniform("InSampler", target, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}
		return sceneCopy.getColorTextureView();
	}

	private static void composite(CommandEncoder encoder, RenderPipeline pipeline, Layer layer, GpuTextureView target, GpuBuffer gpuData,
								  @Nullable GpuBuffer options, @Nullable GpuTextureView scene) {
		// Made (and cleared) before the pass opens: no texture can be cleared while one is open.
		GpuTextureView none = pipeline != MyriadPipelines.HIGHLIGHT_COMPOSITE ? blank() : null;
		try (RenderPass pass = begin(encoder, pipeline, target, gpuData, layer.compositeRect)) {
			if (options != null) pass.setUniform("FillOptions", options);
			GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			pass.setUniform("SpreadSampler", layer.spread.getColorTextureView(), nearest);
			pass.setUniform("GlowSampler", layer.glow.getColorTextureView(), nearest);
			// Only addons' fills read the depth; the main pass doesn't take it.
			if (pipeline != MyriadPipelines.HIGHLIGHT_COMPOSITE) {
				pass.setUniform("MaskDepth", layer.depth != null ? layer.depth : none, nearest);
				pass.setUniform("SceneColor", scene != null ? scene : none, nearest);
				pass.setUniform("SceneDepth", scene != null && layer.sceneDepth != null ? layer.sceneDepth : none, nearest);
			}
			pass.draw(3, 1, 0, 0);
		}
	}

	/** A clear texture of one pixel, standing in for a depth or scene texture a pass doesn't have (a fill then reads none). */
	private static GpuTextureView blank() {
		if (blank == null) {
			blank = new TextureTarget("Myriad highlights blank", 1, 1, GpuFormat.RGBA8_UNORM, null);
			RenderSystem.getDevice().createCommandEncoder().clearColorTexture(blank.getColorTexture(), new Vector4f(0));
		}
		return blank.getColorTextureView();
	}

	private static RenderPass begin(CommandEncoder encoder, RenderPipeline pipeline, GpuTextureView target, GpuBuffer gpuData, int[] rect) {
		RenderPass pass = encoder.createRenderPass(() -> "Myriad highlights", target, Optional.empty(), null, OptionalDouble.empty());
		pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
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

	/**
	 * Writes the header and highlights {@code from} to {@code to} (as ids from 0) to {@code ring}'s current slot.
	 * {@code toView} takes the masks' clip space back to view space (x right, y up, the camera looking down -z, in
	 * blocks): the inverse of the projection they were drawn with, or null if their depth can't be read. {@code toWorld}
	 * takes view space on to the world's axes, still from the camera (a preview: the subject's own space); null keeps
	 * view space.
	 */
	static GpuBuffer write(MappableRingBuffer ring, int radius, int boundary, float[] data, int from, int to, @Nullable Matrix4fc toView,
						   @Nullable Matrix4fc toWorld) {
		GpuBuffer gpu = ring.currentBuffer();
		try (GpuBufferSlice.MappedView view = gpu.map(false, true)) {
			FloatBuffer out = view.data().order(ByteOrder.nativeOrder()).asFloatBuffer();
			out.put(radius).put((float) Minecraft.getInstance().getWindow().getGuiScale()).put(to - from).put(boundary);
			out.put((float) ((System.nanoTime() - START) / 1e9 % 3600)).put(0).put(radius > boundary ? 1 : 0).put(glowScale(radius));
			out.put(toView != null ? 1 : 0).put(RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1 : 0).put(0).put(0);
			float[] columns = new float[16];
			(toView != null ? toView : IDENTITY).get(columns);
			out.put(columns);
			(toWorld != null ? toWorld : IDENTITY).get(columns);
			out.put(columns);
			out.put(data, from * HighlightTable.FLOATS, (to - from) * HighlightTable.FLOATS);
		}
		return gpu;
	}

	private static void resize(Layer layer, int w, int h) {
		int hw = (w + 1) / 2, hh = (h + 1) / 2;
		if (layer.spread == null) {
			layer.resolved = new TextureTarget("Myriad highlights resolved (" + layer.name + ")", w, h, GpuFormat.RGBA8_UNORM, null);
			layer.spread = new TextureTarget("Myriad highlights spread (" + layer.name + ")", w, h, GpuFormat.RGBA16_UNORM, null);
			layer.glowRow = new TextureTarget("Myriad highlights glow rows (" + layer.name + ")", hw, hh, GpuFormat.RGBA16_UNORM, null);
			layer.glow = new TextureTarget("Myriad highlights glow (" + layer.name + ")", hw, hh, GpuFormat.RGBA16_UNORM, null);
		} else if (layer.spread.width != w || layer.spread.height != h) {
			layer.resolved.resize(w, h);
			layer.spread.resize(w, h);
			layer.glowRow.resize(hw, hh);
			layer.glow.resize(hw, hh);
		}
	}
}
