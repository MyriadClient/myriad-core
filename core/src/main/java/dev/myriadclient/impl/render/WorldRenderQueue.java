package dev.myriadclient.impl.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.myriadclient.api.render.ShapeBuilder;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * The per-frame shape target: collects shapes during Render3DEvent and submits them to the level renderer afterwards.
 * <p>
 * Shapes are stored relative to the camera, subtracted while still in doubles. Absolute world coordinates don't fit in
 * a float far from spawn (at 1,000,000 a float only resolves 1/16 of a block), which makes boxes jitter as the camera
 * moves and lose their corners.
 */
public final class WorldRenderQueue extends ShapeBuilder {
	public static final WorldRenderQueue INSTANCE = new WorldRenderQueue();

	private final Layer depth = new Layer();
	private final Layer xray = new Layer();
	private final FloatArrayList tracers = new FloatArrayList();
	private final IntArrayList tracerColors = new IntArrayList();
	private final FloatArrayList tracerWidths = new FloatArrayList();

	private WorldRenderQueue() {
	}

	/** The inverse of this frame's view bobbing (walking bob and hurt shake), which vanilla folds into the projection. */
	private final Matrix4f inverseBob = new Matrix4f();

	/** Records this frame's view bobbing, so tracers can start at the true centre of the screen. */
	public void viewBob(Matrix4fc bob) {
		bob.invert(inverseBob);
	}

	/** Sets the camera position shapes are stored relative to; called before Render3DEvent is posted. */
	public void begin(Vec3 camera) {
		originX = camera.x;
		originY = camera.y;
		originZ = camera.z;
		lineWidth = DEFAULT_LINE_WIDTH;
	}

	public void tracer(Vec3 to, int color) {
		tracers.add((float) (to.x - originX));
		tracers.add((float) (to.y - originY));
		tracers.add((float) (to.z - originZ));
		tracerColors.add(color);
		tracerWidths.add(lineWidth);
	}

	@Override
	protected void emitQuad(boolean throughWalls, float ax, float ay, float az, float bx, float by, float bz,
							float cx, float cy, float cz, float dx, float dy, float dz, int color) {
		(throughWalls ? xray : depth).quad(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, color);
	}

	@Override
	protected void emitLine(boolean throughWalls, float ax, float ay, float az, float bx, float by, float bz, int fromColor, int toColor, float width) {
		(throughWalls ? xray : depth).line(ax, ay, az, bx, by, bz, fromColor, toColor, width);
	}

	/** Submits everything queued to the level renderer, then clears the queue. */
	public void submit(SubmitNodeCollector collector, PoseStack poseStack, Camera camera) {
		// Tracers start just in front of the camera (the origin here), at the point the bobbing projection puts in the
		// middle of the screen: straight ahead with the bob undone, so they stay on the crosshair while you walk.
		if (!tracerColors.isEmpty()) {
			Vector3f view = inverseBob.transformPosition(new Vector3f(0, 0, -0.5f));
			Vector3f world = camera.rotation().transform(view);
			Vec3 look = new Vec3(world.x, world.y, world.z);
			for (int i = 0; i < tracerColors.size(); i++) {
				int c = tracerColors.getInt(i);
				xray.line((float) look.x, (float) look.y, (float) look.z, tracers.getFloat(i * 3), tracers.getFloat(i * 3 + 1), tracers.getFloat(i * 3 + 2), c, c, tracerWidths.getFloat(i));
			}
		}
		depth.submit(collector, poseStack, MyriadPipelines.QUADS, MyriadPipelines.LINES);
		xray.submit(collector, poseStack, MyriadPipelines.QUADS_XRAY, MyriadPipelines.LINES_XRAY);
		tracers.clear();
		tracerColors.clear();
		tracerWidths.clear();
		lineWidth = DEFAULT_LINE_WIDTH;
	}

	private static final class Layer {
		FloatArrayList quads = new FloatArrayList();
		IntArrayList quadColors = new IntArrayList();
		FloatArrayList lines = new FloatArrayList();
		IntArrayList lineColors = new IntArrayList();
		FloatArrayList lineWidths = new FloatArrayList();

		void quad(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz, int color) {
			quads.add(ax); quads.add(ay); quads.add(az);
			quads.add(bx); quads.add(by); quads.add(bz);
			quads.add(cx); quads.add(cy); quads.add(cz);
			quads.add(dx); quads.add(dy); quads.add(dz);
			quadColors.add(color);
		}

		void line(float ax, float ay, float az, float bx, float by, float bz, int ca, int cb, float width) {
			lines.add(ax); lines.add(ay); lines.add(az);
			lines.add(bx); lines.add(by); lines.add(bz);
			lineColors.add(ca);
			lineColors.add(cb);
			lineWidths.add(width);
		}

		void submit(SubmitNodeCollector collector, PoseStack poseStack, RenderType quadType, RenderType lineType) {
			// The geometry is drawn later in the frame, so it gets its own copy of the lists.
			if (!quadColors.isEmpty()) {
				FloatArrayList q = quads;
				IntArrayList qc = quadColors;
				quads = new FloatArrayList(q.size());
				quadColors = new IntArrayList(qc.size());
				collector.submitCustomGeometry(poseStack, quadType, (pose, buf) -> {
					for (int n = 0; n < qc.size(); n++) {
						int c = qc.getInt(n);
						for (int v = 0; v < 4; v++) {
							int i = n * 12 + v * 3;
							buf.addVertex(pose, q.getFloat(i), q.getFloat(i + 1), q.getFloat(i + 2)).setColor(c);
						}
					}
				});
			}
			if (!lineColors.isEmpty()) {
				FloatArrayList l = lines;
				IntArrayList lc = lineColors;
				FloatArrayList lw = lineWidths;
				lines = new FloatArrayList(l.size());
				lineColors = new IntArrayList(lc.size());
				lineWidths = new FloatArrayList(lw.size());
				collector.submitCustomGeometry(poseStack, lineType, (pose, buf) -> {
					for (int n = 0; n < lw.size(); n++) emitLine(buf, pose, l, n * 6, lc.getInt(n * 2), lc.getInt(n * 2 + 1), lw.getFloat(n));
				});
			}
		}

		private static void emitLine(VertexConsumer buf, PoseStack.Pose pose, FloatArrayList l, int i, int ca, int cb, float width) {
			float ax = l.getFloat(i), ay = l.getFloat(i + 1), az = l.getFloat(i + 2);
			float bx = l.getFloat(i + 3), by = l.getFloat(i + 4), bz = l.getFloat(i + 5);
			float nx = bx - ax, ny = by - ay, nz = bz - az;
			float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len < 1e-5f) return;
			nx /= len;
			ny /= len;
			nz /= len;
			buf.addVertex(pose, ax, ay, az).setColor(ca).setNormal(pose, nx, ny, nz).setLineWidth(width);
			buf.addVertex(pose, bx, by, bz).setColor(cb).setNormal(pose, nx, ny, nz).setLineWidth(width);
		}
	}
}
