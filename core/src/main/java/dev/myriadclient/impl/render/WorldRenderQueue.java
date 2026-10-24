package dev.myriadclient.impl.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.myriadclient.api.render.ShapeBuilder;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.SubmitNodeCollector;
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

	private final ShapeLayer depth = new ShapeLayer();
	private final ShapeLayer xray = new ShapeLayer();
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
		depth.submit(collector, poseStack, MyriadPipelines.FACES, MyriadPipelines.LINES);
		xray.submit(collector, poseStack, MyriadPipelines.FACES_XRAY, MyriadPipelines.LINES_XRAY);
		tracers.clear();
		tracerColors.clear();
		tracerWidths.clear();
		lineWidth = DEFAULT_LINE_WIDTH;
	}
}
