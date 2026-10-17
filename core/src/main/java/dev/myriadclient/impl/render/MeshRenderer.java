package dev.myriadclient.impl.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
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
 * Draws every live {@link WorldMesh} once per frame, in one render pass right after vanilla's translucent features
 * (where per-frame shapes are drawn too). Meshes outside the view frustum are skipped. Each mesh is one draw per layer
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

	private MeshRenderer() {
	}

	public static void add(WorldMesh mesh) {
		live.add(mesh);
	}

	public static void remove(WorldMesh mesh) {
		live.remove(mesh);
	}

	/** Meshes holding GPU buffers right now (for debugging and the console). */
	public static int liveCount() {
		return live.size();
	}

	public static void draw(CameraRenderState camera) {
		if (live.isEmpty()) return;
		Frustum frustum = camera.cullFrustum;
		Vec3 cam = camera.pos;
		visible.clear();
		transforms.clear();
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
		if (visible.isEmpty()) return;

		// Index buffers are shared and grow on demand; size them before the pass opens, since growing writes to the GPU.
		RenderSystem.AutoStorageIndexBuffer quadIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		RenderSystem.AutoStorageIndexBuffer lineIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.LINES);
		GpuBuffer quadIndexBuffer = maxQuadIndices > 0 ? quadIndices.getBuffer(maxQuadIndices) : null;
		GpuBuffer lineIndexBuffer = maxLineIndices > 0 ? lineIndices.getBuffer(maxLineIndices) : null;

		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
			.createRenderPass(() -> "Myriad meshes", main.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty())) {
			for (int layer = 0; layer < PIPELINES.length; layer++) {
				boolean lines = (layer & 1) == 1;
				boolean bound = false;
				for (int i = 0; i < visible.size(); i++) {
					WorldMesh mesh = visible.get(i);
					int count = mesh.indexCount(layer);
					GpuBuffer vertices = mesh.buffer(layer);
					if (count == 0 || vertices == null) continue;
					if (!bound) {
						pass.setPipeline(PIPELINES[layer]);
						RenderSystem.bindDefaultUniforms(pass);
						if (lines) pass.setIndexBuffer(lineIndexBuffer, lineIndices.type());
						else pass.setIndexBuffer(quadIndexBuffer, quadIndices.type());
						bound = true;
					}
					pass.setUniform("DynamicTransforms", transforms.get(i));
					pass.setVertexBuffer(0, vertices.slice());
					pass.drawIndexed(count, 1, 0, 0, 0);
				}
			}
		}
	}
}
