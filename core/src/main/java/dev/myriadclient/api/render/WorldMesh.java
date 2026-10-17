package dev.myriadclient.api.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.impl.render.GpuGarbage;
import dev.myriadclient.impl.render.MeshRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Shapes kept on the GPU and drawn every frame until you rebuild or clear them. Building costs about what drawing the
 * same shapes with {@code Renderer3D} for one frame does; after that a frame costs one draw call, with no per-frame
 * handler and nothing re-uploaded. Use it for anything that changes less often than every frame:
 *
 * <pre>{@code
 * private final WorldMesh mesh = WorldMesh.create(this);   // drawn while this module is on, freed when it turns off
 *
 * @Subscribe
 * private void onTick(TickEvent.Post e) {
 *     if (!changed) return;
 *     mesh.build(m -> {
 *         for (BlockPos pos : found) m.blockShape(pos, fill, line, ShapeMode.BOTH, true);
 *     });
 * }
 * }</pre>
 *
 * For data that lives in chunks (blocks, block entities), {@code ChunkCache} keeps one mesh per chunk and rebuilds
 * only the chunks that change.
 * <p>
 * Colours are fixed when the mesh is built: rebuild it when a colour setting or the theme changes. Translucent fills
 * aren't depth-sorted per frame, which only shows where several translucent fills overlap. Build and clear on the
 * render thread (ticks, events, render callbacks).
 */
public final class WorldMesh implements AutoCloseable {
	private final GpuBuffer[] buffers = new GpuBuffer[MeshBuilder.LAYERS];
	private final int[] indexCounts = new int[MeshBuilder.LAYERS];
	private final String label;
	private double originX, originY, originZ;
	private AABB bounds;
	private boolean active, visible = true, closed, registered;

	private WorldMesh(String label, boolean active) {
		this.label = label;
		this.active = active;
	}

	/** A mesh you manage yourself: drawn whenever it has shapes, until {@link #close}d. */
	public static WorldMesh create() {
		return new WorldMesh("Myriad mesh", true);
	}

	/** A mesh drawn only while {@code owner} is enabled. Turning the module off clears it, so rebuild it in onEnable. */
	public static WorldMesh create(Module owner) {
		WorldMesh mesh = new WorldMesh("Myriad mesh (" + owner.name() + ")", false);
		owner.whileEnabled(() -> mesh.active = true, () -> {
			mesh.active = false;
			mesh.clear();
		});
		return mesh;
	}

	/** Replaces the mesh's shapes with what {@code shapes} adds, stored relative to the camera's block. */
	public void build(Consumer<MeshBuilder> shapes) {
		Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		build(Math.floor(camera.x), Math.floor(camera.y), Math.floor(camera.z), shapes);
	}

	/**
	 * Replaces the mesh's shapes, stored relative to {@code origin}. Pick an origin near the shapes (a chunk corner, a
	 * block): positions are kept as floats relative to it, which only stays exact within a few thousand blocks.
	 */
	public void build(double originX, double originY, double originZ, Consumer<MeshBuilder> shapes) {
		if (closed) throw new IllegalStateException("WorldMesh is closed");
		RenderSystem.assertOnRenderThread();
		MeshBuilder builder = new MeshBuilder(originX, originY, originZ);
		MeshData[] data = new MeshData[MeshBuilder.LAYERS];
		try {
			try {
				shapes.accept(builder);
			} finally {
				// Finish every layer even if the callback threw, so the shared buffers are rewound for the next mesh.
				for (int i = 0; i < MeshBuilder.LAYERS; i++) data[i] = builder.finish(i);
			}
			freeBuffers();
			for (int i = 0; i < MeshBuilder.LAYERS; i++) {
				if (data[i] == null) continue;
				buffers[i] = RenderSystem.getDevice().createBuffer(() -> label, GpuBuffer.USAGE_VERTEX, data[i].vertexBuffer());
				indexCounts[i] = data[i].drawState().indexCount();
			}
		} finally {
			for (MeshData d : data) if (d != null) d.close();
			builder.release();
		}
		this.originX = originX;
		this.originY = originY;
		this.originZ = originZ;
		boolean empty = isEmpty();
		bounds = empty ? null : new AABB(originX + builder.minX, originY + builder.minY, originZ + builder.minZ,
			originX + builder.maxX, originY + builder.maxY, originZ + builder.maxZ);
		setRegistered(!empty);
	}

	/** Removes every shape (and frees the GPU memory). The mesh can be built again. */
	public void clear() {
		freeBuffers();
		bounds = null;
		setRegistered(false);
	}

	/** Hides or shows the mesh without rebuilding it. */
	public void setVisible(boolean visible) {
		this.visible = visible;
	}

	public boolean isVisible() {
		return visible;
	}

	public boolean isEmpty() {
		for (GpuBuffer b : buffers) if (b != null) return false;
		return true;
	}

	/** Frees the mesh for good. */
	@Override
	public void close() {
		clear();
		closed = true;
	}

	private void freeBuffers() {
		for (int i = 0; i < buffers.length; i++) {
			if (buffers[i] != null) GpuGarbage.close(buffers[i]);
			buffers[i] = null;
			indexCounts[i] = 0;
		}
	}

	private void setRegistered(boolean on) {
		if (on == registered) return;
		registered = on;
		if (on) MeshRenderer.add(this);
		else MeshRenderer.remove(this);
	}

	// ---- for the renderer ------------------------------------------------------------------------------------------

	@ApiStatus.Internal
	public boolean shouldDraw() {
		return active && visible && registered;
	}

	@ApiStatus.Internal
	public @Nullable GpuBuffer buffer(int layer) {
		return buffers[layer];
	}

	@ApiStatus.Internal
	public int indexCount(int layer) {
		return indexCounts[layer];
	}

	@ApiStatus.Internal
	public AABB bounds() {
		return bounds;
	}

	@ApiStatus.Internal
	public double originX() {
		return originX;
	}

	@ApiStatus.Internal
	public double originY() {
		return originY;
	}

	@ApiStatus.Internal
	public double originZ() {
		return originZ;
	}
}
