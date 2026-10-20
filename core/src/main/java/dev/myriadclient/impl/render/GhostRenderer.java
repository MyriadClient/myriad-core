package dev.myriadclient.impl.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.myriadclient.api.event.events.Render3DEvent;
import dev.myriadclient.api.render.HighlightStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** {@link dev.myriadclient.api.render.Ghosts}: an entity's renderer run again at a place of your choosing, through a {@link GhostCollector}. */
public final class GhostRenderer {
	/** This frame's camera, as the world renders with it; set before {@code Render3DEvent} is posted. */
	private static @Nullable CameraRenderState camera;

	private GhostRenderer() {
	}

	public static void beginFrame(CameraRenderState frameCamera) {
		camera = frameCamera;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public static void submit(Render3DEvent event, LivingEntityRenderState state, Vec3 feet, float alpha, @Nullable HighlightStyle style, int color) {
		if (camera == null) return;
		// Highlighted as an entity standing there would be: its outline colour points every layer at the highlight.
		AABB around = new AABB(feet.x - 0.5, feet.y, feet.z - 0.5, feet.x + 0.5, feet.y + 2.1, feet.z + 0.5);
		state.outlineColor = style == null ? 0 : HighlightRenderer.INSTANCE.ghost(style, color, around);
		EntityRenderer renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(state);
		if (renderer == null) return;
		state.nameTag = null;
		state.shadowPieces.clear();
		Vec3 cam = camera.pos;
		PoseStack pose = event.matrices();
		pose.pushPose();
		pose.translate(feet.x - cam.x, feet.y - cam.y, feet.z - cam.z);
		renderer.submit(state, pose, GhostCollector.of(event.submits(), Math.clamp(alpha, 0, 1)), camera);
		pose.popPose();
	}
}
