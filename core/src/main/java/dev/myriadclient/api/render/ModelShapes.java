package dev.myriadclient.api.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;

/**
 * An entity's model as shapes, posed and animated like the model: a flat-colour fill or a wireframe of its boxes.
 * Call while the model is being submitted (e.g. from a mixin on the entity renderer), with the same pose stack.
 */
public final class ModelShapes {
	private static final int[][] EDGES = {
		{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}
	};

	private ModelShapes() {
	}

	/** The model drawn again in one translucent colour (ARGB). */
	public static <S> void fill(SubmitNodeCollector submits, PoseStack poseStack, Model<? super S> model, S state, int color, boolean throughWalls) {
		submits.submitModel(model, state, poseStack, RenderLayers.flatModel(throughWalls), LightCoordsUtil.FULL_BRIGHT,
			OverlayTexture.NO_OVERLAY, color, null, 0, null);
	}

	/** The edges of every box in the model (ARGB), {@code width} pixels wide. */
	public static <S> void wireframe(SubmitNodeCollector submits, PoseStack poseStack, Model<? super S> model, S state, int color, float width,
									 boolean throughWalls) {
		submits.submitCustomGeometry(poseStack, RenderLayers.lines(throughWalls), (pose, buffer) -> {
			// Drawn when the frame's models are, after other entities may have posed the same (shared) model.
			model.setupAnim(state);
			PoseStack stack = new PoseStack();
			stack.last().set(pose);
			edges(model.root(), stack, buffer, color, width);
		});
	}

	private static void edges(ModelPart part, PoseStack stack, VertexConsumer buffer, int color, float width) {
		if (!part.visible) return;
		stack.pushPose();
		part.translateAndRotate(stack);
		if (!part.skipDraw) {
			PoseStack.Pose pose = stack.last();
			for (ModelPart.Cube cube : part.cubes) {
				float x0 = cube.minX / 16f, y0 = cube.minY / 16f, z0 = cube.minZ / 16f;
				float x1 = cube.maxX / 16f, y1 = cube.maxY / 16f, z1 = cube.maxZ / 16f;
				float[][] c = {
					{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}
				};
				for (int[] e : EDGES) {
					float[] a = c[e[0]], b = c[e[1]];
					float nx = b[0] - a[0], ny = b[1] - a[1], nz = b[2] - a[2];
					float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
					if (len < 1e-6f) continue;
					nx /= len;
					ny /= len;
					nz /= len;
					buffer.addVertex(pose, a[0], a[1], a[2]).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
					buffer.addVertex(pose, b[0], b[1], b[2]).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
				}
			}
		}
		for (ModelPart child : part.children.values()) edges(child, stack, buffer, color, width);
		stack.popPose();
	}
}
