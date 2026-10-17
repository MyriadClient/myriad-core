package dev.myriadclient.api.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import org.jetbrains.annotations.Nullable;

/**
 * Records shapes for a {@link WorldMesh}. Hand one out only through {@link WorldMesh#build} (or a {@code ChunkCache}
 * mesher); it is valid until that call returns. Coordinates are absolute world coordinates, like everywhere else.
 */
public final class MeshBuilder extends ShapeBuilder {
	/** Layers, in draw order. Quads and lines are separate buffers because they use different vertex formats. */
	static final int QUADS = 0, LINES = 1, QUADS_XRAY = 2, LINES_XRAY = 3, LAYERS = 4;

	// Building happens on the render thread, so one set of native buffers is reused for every mesh. A mesh built while
	// another is building (a mesher that builds a mesh) gets its own.
	private static final ByteBufferBuilder[] SHARED = new ByteBufferBuilder[LAYERS];
	private static boolean sharedInUse;

	private final ByteBufferBuilder[] memory;
	private final boolean ownsMemory;
	private final BufferBuilder[] layers = new BufferBuilder[LAYERS];
	float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
	float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

	MeshBuilder(double originX, double originY, double originZ) {
		this.originX = originX;
		this.originY = originY;
		this.originZ = originZ;
		if (!sharedInUse) {
			sharedInUse = true;
			ownsMemory = false;
			memory = SHARED;
		} else {
			ownsMemory = true;
			memory = new ByteBufferBuilder[LAYERS];
		}
	}

	private BufferBuilder layer(int index) {
		BufferBuilder b = layers[index];
		if (b == null) {
			if (memory[index] == null) memory[index] = new ByteBufferBuilder(4096);
			boolean lines = index == LINES || index == LINES_XRAY;
			b = new BufferBuilder(memory[index], lines ? PrimitiveTopology.LINES : PrimitiveTopology.QUADS,
				lines ? DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH : DefaultVertexFormat.POSITION_COLOR);
			layers[index] = b;
		}
		return b;
	}

	private void include(float x, float y, float z) {
		if (x < minX) minX = x;
		if (y < minY) minY = y;
		if (z < minZ) minZ = z;
		if (x > maxX) maxX = x;
		if (y > maxY) maxY = y;
		if (z > maxZ) maxZ = z;
	}

	@Override
	protected void emitQuad(boolean throughWalls, float ax, float ay, float az, float bx, float by, float bz,
							float cx, float cy, float cz, float dx, float dy, float dz, int color) {
		BufferBuilder b = layer(throughWalls ? QUADS_XRAY : QUADS);
		b.addVertex(ax, ay, az).setColor(color);
		b.addVertex(bx, by, bz).setColor(color);
		b.addVertex(cx, cy, cz).setColor(color);
		b.addVertex(dx, dy, dz).setColor(color);
		include(ax, ay, az);
		include(bx, by, bz);
		include(cx, cy, cz);
		include(dx, dy, dz);
	}

	@Override
	protected void emitLine(boolean throughWalls, float ax, float ay, float az, float bx, float by, float bz, int fromColor, int toColor, float width) {
		float nx = bx - ax, ny = by - ay, nz = bz - az;
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len < 1e-5f) return;
		nx /= len;
		ny /= len;
		nz /= len;
		BufferBuilder b = layer(throughWalls ? LINES_XRAY : LINES);
		b.addVertex(ax, ay, az).setColor(fromColor).setNormal(nx, ny, nz).setLineWidth(width);
		b.addVertex(bx, by, bz).setColor(toColor).setNormal(nx, ny, nz).setLineWidth(width);
		include(ax, ay, az);
		include(bx, by, bz);
	}

	/** The finished geometry of one layer, or null if nothing was added to it. The caller closes it. */
	@Nullable MeshData finish(int layer) {
		BufferBuilder b = layers[layer];
		return b == null ? null : b.build();
	}

	/**
	 * Gives the native memory back. Call once every layer was {@link #finish finished} and its MeshData closed (closing
	 * the last result rewinds the shared buffers for the next mesh).
	 */
	void release() {
		if (ownsMemory) {
			for (ByteBufferBuilder m : memory) if (m != null) m.close();
		} else {
			sharedInUse = false;
		}
	}
}
