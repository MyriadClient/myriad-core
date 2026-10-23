package dev.myriadclient.impl.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * Quads and lines collected for one submission, then handed to a {@link SubmitNodeCollector} with the render types to
 * draw them with: the world's shapes ({@link WorldRenderQueue}) and a preview's decorations.
 */
public final class ShapeLayer {
	private FloatArrayList quads = new FloatArrayList();
	private IntArrayList quadColors = new IntArrayList();
	private FloatArrayList lines = new FloatArrayList();
	private IntArrayList lineColors = new IntArrayList();
	private FloatArrayList lineWidths = new FloatArrayList();

	public void quad(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz, int color) {
		quads.add(ax); quads.add(ay); quads.add(az);
		quads.add(bx); quads.add(by); quads.add(bz);
		quads.add(cx); quads.add(cy); quads.add(cz);
		quads.add(dx); quads.add(dy); quads.add(dz);
		quadColors.add(color);
	}

	public void line(float ax, float ay, float az, float bx, float by, float bz, int ca, int cb, float width) {
		lines.add(ax); lines.add(ay); lines.add(az);
		lines.add(bx); lines.add(by); lines.add(bz);
		lineColors.add(ca);
		lineColors.add(cb);
		lineWidths.add(width);
	}

	public void submit(SubmitNodeCollector collector, PoseStack poseStack, RenderType quadType, RenderType lineType) {
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
