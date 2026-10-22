package dev.myriadclient.impl.render;

import dev.myriadclient.api.render.HighlightStyle;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import java.util.Arrays;

/**
 * This frame's highlights, by id: what each looks like, where it is and its shader data, with the camera they're
 * measured and projected from. Merge groups are handed out here, so highlights that look alike connect.
 */
final class HighlightTable {
	/** vec4s per highlight in the data buffer: top colour, bottom colour, gradient rectangle, shape, fill. */
	static final int TEXELS = 5;
	static final int FLOATS = TEXELS * 4;

	int count;
	HighlightStyle[] styles = new HighlightStyle[64];
	int[] colors = new int[64];
	int[] groupOf = new int[64];
	private byte[] layerOf = new byte[64];
	/** Per highlight: the box the gradient spans, then a box sure to contain the whole silhouette (12 doubles). */
	private double[] bounds = new double[12 * 64];
	/** Per highlight: distance from the camera to its nearest part, for distance scaling. */
	private double[] nearest = new double[64];
	float[] data = new float[FLOATS * 64];
	/** This frame's merge groups: highlights that look alike (same style and colour) share one, and connect. */
	private final Object2IntOpenHashMap<GroupKey> groups = new Object2IntOpenHashMap<>();

	record GroupKey(HighlightStyle style, int rgb) {
	}

	// This frame's camera, captured before the level is drawn.
	final Matrix4f projection = new Matrix4f();
	private final Matrix4f view = new Matrix4f();
	private final Matrix4f viewProjection = new Matrix4f();
	private final Vector4f corner = new Vector4f();
	double camX, camY, camZ;

	void clear() {
		count = 0;
		groups.clear();
	}

	void camera(Vec3 pos, Matrix4fc modelView) {
		camX = pos.x;
		camY = pos.y;
		camZ = pos.z;
		view.set(modelView);
	}

	/** Records a highlight in {@code layer}; returns its id, or -1 when there are too many. */
	int add(int layer, HighlightStyle style, int color, AABB gradientBox, AABB around) {
		if (count >= HighlightRenderer.MAX_HIGHLIGHTS) return -1;
		int id = count++;
		if (styles.length < count) {
			int n = styles.length * 2;
			styles = Arrays.copyOf(styles, n);
			colors = Arrays.copyOf(colors, n);
			groupOf = Arrays.copyOf(groupOf, n);
			layerOf = Arrays.copyOf(layerOf, n);
			nearest = Arrays.copyOf(nearest, n);
			bounds = Arrays.copyOf(bounds, n * 12);
			data = Arrays.copyOf(data, n * FLOATS);
		}
		GroupKey key = new GroupKey(style, color & 0xFFFFFF);
		int group = groups.getInt(key);
		if (group == 0) groups.put(key, group = groups.size() % 255 + 1);
		styles[id] = style;
		colors[id] = color;
		groupOf[id] = group;
		layerOf[id] = (byte) layer;
		putBox(id * 12, gradientBox);
		putBox(id * 12 + 6, around);
		nearest[id] = distance(gradientBox);
		return id;
	}

	/** Grows highlight {@code id}'s boxes to cover {@code box}; its widths follow its nearest part. */
	void grow(int id, AABB box) {
		int b = id * 12;
		for (int o : new int[]{b, b + 6}) {
			bounds[o] = Math.min(bounds[o], box.minX);
			bounds[o + 1] = Math.min(bounds[o + 1], box.minY);
			bounds[o + 2] = Math.min(bounds[o + 2], box.minZ);
			bounds[o + 3] = Math.max(bounds[o + 3], box.maxX);
			bounds[o + 4] = Math.max(bounds[o + 4], box.maxY);
			bounds[o + 5] = Math.max(bounds[o + 5], box.maxZ);
		}
		nearest[id] = Math.min(nearest[id], distance(box));
	}

	private double distance(AABB box) {
		return Math.sqrt(box.distanceToSqr(new Vec3(camX, camY, camZ)));
	}

	private void putBox(int b, AABB box) {
		bounds[b] = box.minX;
		bounds[b + 1] = box.minY;
		bounds[b + 2] = box.minZ;
		bounds[b + 3] = box.maxX;
		bounds[b + 4] = box.maxY;
		bounds[b + 5] = box.maxZ;
	}

	/**
	 * Fills in the shader data of highlights {@code from} to {@code to} (exclusive); returns {reach, outline} in pixels,
	 * the most any of them needs.
	 */
	float[] fillData(int from, int to) {
		float scale = (float) Minecraft.getInstance().getWindow().getGuiScale();
		float maxReach = 0, maxOutline = 0;
		for (int id = from; id < to; id++) {
			HighlightStyle style = styles[id];
			int color = colors[id], i = id * FLOATS;
			float alpha = (color >>> 24) / 255f;
			putColor(i, color, alpha);
			if (style.hasGradient()) putColor(i + 4, style.gradient(), alpha * (style.gradient() >>> 24) / 255f);
			else System.arraycopy(data, i, data, i + 4, 4);
			// i + 8..11: the gradient rectangle, from screenAreas.
			// Thinner with distance (to half), but an outline never drops below a pixel.
			float far = style.widthScale(nearest[id]);
			float outline = style.outlineWidth() * scale * far, glow = style.glow() * scale * far;
			if (outline > 0) outline = Math.max(1, outline);
			data[i + 12] = outline;
			data[i + 13] = glow;
			data[i + 14] = style.glowStrength();
			data[i + 15] = style.fillOpacity();
			data[i + 16] = style.fill().shaderId();
			data[i + 17] = style.dotSpacing() * scale;
			data[i + 18] = style.dotSize() * scale * 0.5f;
			// Bit 0: hidden parts are dropped; above it the merge group (the mesh mask shader reads it from here).
			data[i + 19] = (style.throughWalls() ? 0 : 1) + 2 * groupOf[id];
			maxReach = Math.max(maxReach, outline + glow);
			maxOutline = Math.max(maxOutline, outline);
		}
		return new float[]{maxReach, maxOutline};
	}

	private void putColor(int i, int argb, float alpha) {
		data[i] = (argb >> 16 & 0xFF) / 255f;
		data[i + 1] = (argb >> 8 & 0xFF) / 255f;
		data[i + 2] = (argb & 0xFF) / 255f;
		data[i + 3] = alpha;
	}

	/**
	 * Projects every highlight with this frame's camera: fills in the gradient rectangles and returns, per layer, the
	 * framebuffer area {x0, y0, x1, y1} that holds its silhouettes (null if none is on screen). Pixel rows count up from
	 * the bottom of the image (OpenGL) or down from the top (Vulkan), the same way texel rows and gl_FragCoord do on
	 * each, so the shaders and scissor agree either way.
	 */
	int[][] screenAreas(int layers, int w, int h) {
		projection.mul(view, viewProjection);
		float[] r = new float[4];
		int[][] box = new int[layers][];
		for (int l = 0; l < box.length; l++) box[l] = new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (int id = 0; id < count; id++) {
			int b = id * 12, i = id * FLOATS;
			if (!project(b, w, h, r)) {
				r[0] = 0;
				r[1] = 0;
				r[2] = w;
				r[3] = h;
			}
			data[i + 8] = r[0];
			data[i + 9] = r[1];
			data[i + 10] = r[2];
			data[i + 11] = r[3];
			if (!project(b + 6, w, h, r)) {
				r[0] = 0;
				r[1] = 0;
				r[2] = w;
				r[3] = h;
			}
			int[] a = box[layerOf[id]];
			a[0] = Math.min(a[0], (int) Math.floor(r[0]));
			a[1] = Math.min(a[1], (int) Math.floor(r[1]));
			a[2] = Math.max(a[2], (int) Math.ceil(r[2]));
			a[3] = Math.max(a[3], (int) Math.ceil(r[3]));
		}
		int[][] areas = new int[layers][];
		for (int l = 0; l < layers; l++) {
			int[] a = box[l];
			int x0 = Math.max(0, a[0] - 1), y0 = Math.max(0, a[1] - 1), x1 = Math.min(w, a[2] + 1), y1 = Math.min(h, a[3] + 1);
			areas[l] = x1 <= x0 || y1 <= y0 ? null : new int[]{x0, y0, x1, y1};
		}
		return areas;
	}

	/** The pixel rectangle around the box at {@code bounds[b]}; false if part of it is behind the camera. */
	private boolean project(int b, int w, int h, float[] out) {
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (int k = 0; k < 8; k++) {
			double x = bounds[b + ((k & 1) == 0 ? 0 : 3)], y = bounds[b + ((k & 2) == 0 ? 1 : 4)], z = bounds[b + ((k & 4) == 0 ? 2 : 5)];
			viewProjection.transform(corner.set((float) (x - camX), (float) (y - camY), (float) (z - camZ), 1));
			if (corner.w <= 1e-4f) return false;
			float px = (corner.x / corner.w * 0.5f + 0.5f) * w, py = (corner.y / corner.w * 0.5f + 0.5f) * h;
			minX = Math.min(minX, px);
			minY = Math.min(minY, py);
			maxX = Math.max(maxX, px);
			maxY = Math.max(maxY, py);
		}
		out[0] = minX;
		out[1] = minY;
		out[2] = maxX;
		out[3] = maxY;
		return true;
	}
}
