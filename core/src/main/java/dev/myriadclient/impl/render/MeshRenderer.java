package dev.myriadclient.impl.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.myriadclient.api.render.WorldMesh;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Draws every live {@link WorldMesh} once per frame, right after vanilla's translucent features (where per-frame
 * shapes are drawn too). Meshes outside the view frustum are skipped. Each mesh is one draw per layer
 * with its own offset from the camera, so nothing is rebuilt or re-uploaded.
 */
public final class MeshRenderer {
	/** In MeshBuilder's layer order: depth-tested quads and lines, then through-walls quads and lines. */
	private static final RenderPipeline[] PIPELINES = {
		MyriadPipelines.MESH_QUADS, MyriadPipelines.MESH_LINES, MyriadPipelines.MESH_QUADS_XRAY, MyriadPipelines.MESH_LINES_XRAY
	};
	private static final Vector4f WHITE = new Vector4f(1, 1, 1, 1);
	private static final Matrix4f IDENTITY = new Matrix4f();

	private static final ReferenceLinkedOpenHashSet<WorldMesh> live = new ReferenceLinkedOpenHashSet<>();
	private static final ObjectArrayList<WorldMesh> visible = new ObjectArrayList<>();
	private static final ObjectArrayList<GpuBufferSlice> transforms = new ObjectArrayList<>();
	private static final RenderSystem.AutoStorageIndexBuffer QUAD_INDICES = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
	private static final RenderSystem.AutoStorageIndexBuffer LINE_INDICES = RenderSystem.getSequentialBuffer(PrimitiveTopology.LINES);
	private static GpuBuffer quadIndexBuffer, lineIndexBuffer;

	private MeshRenderer() {
	}

	public static void add(WorldMesh mesh) {
		live.add(mesh);
	}

	public static void remove(WorldMesh mesh) {
		live.remove(mesh);
	}

	/** Gets this frame's visible meshes ready to draw; before any pass opens, since it writes to the GPU. */
	public static void prepare(CameraRenderState camera) {
		visible.clear();
		transforms.clear();
		quadIndexBuffer = lineIndexBuffer = null;
		if (live.isEmpty()) return;
		Frustum frustum = camera.cullFrustum;
		Vec3 cam = camera.pos;
		int maxQuadIndices = 0, maxLineIndices = 0;
		Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
		for (WorldMesh mesh : live) {
			if (!mesh.shouldDraw() || !frustum.isVisible(mesh.bounds())) continue;
			visible.add(mesh);
			// A new vector each time: vanilla skips the write when a transform equals the previous one, which a reused
			// (mutated) vector always would.
			Vector3f offset = new Vector3f((float) (mesh.originX() - cam.x), (float) (mesh.originY() - cam.y), (float) (mesh.originZ() - cam.z));
			transforms.add(RenderSystem.getDynamicUniforms().writeTransform(modelView, WHITE, offset, IDENTITY));
			maxQuadIndices = Math.max(maxQuadIndices, Math.max(mesh.indexCount(0), mesh.indexCount(2)));
			maxLineIndices = Math.max(maxLineIndices, Math.max(mesh.indexCount(1), mesh.indexCount(3)));
		}
		if (maxQuadIndices > 0) quadIndexBuffer = QUAD_INDICES.getBuffer(maxQuadIndices);
		if (maxLineIndices > 0) lineIndexBuffer = LINE_INDICES.getBuffer(maxLineIndices);
	}

	/** Draws what {@link #prepare} found in a pass of its own, onto the main target. */
	public static void drawAlone() {
		if (visible.isEmpty()) return;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
			.createRenderPass(() -> "Myriad meshes", main.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty())) {
			draw(pass);
		}
	}

	/** Draws what {@link #prepare} found into {@code pass}, which targets the main color and depth. */
	public static void draw(RenderPass pass) {
		for (int layer = 0; layer < PIPELINES.length; layer++) {
			boolean lines = (layer & 1) == 1;
			boolean bound = false;
			for (int i = 0; i < visible.size(); i++) {
				WorldMesh mesh = visible.get(i);
				int count = mesh.indexCount(layer);
				GpuBuffer vertices = mesh.buffer(layer);
				if (count == 0 || vertices == null) continue;
				if (!bound) {
					pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINES[layer]));
					RenderSystem.bindDefaultUniforms(pass);
					if (lines) pass.setIndexBuffer(lineIndexBuffer, LINE_INDICES.type());
					else pass.setIndexBuffer(quadIndexBuffer, QUAD_INDICES.type());
					bound = true;
				}
				pass.setUniform("DynamicTransforms", transforms.get(i));
				pass.setVertexBuffer(0, vertices.slice());
				pass.drawIndexed(count, 1, 0, 0, 0);
			}
		}
	}
}
