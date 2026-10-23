package dev.myriadclient.impl.render.preview;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.myriadclient.api.ui.widget.Preview;
import dev.myriadclient.impl.render.MyriadPipelines;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws a {@link Preview} into its own texture, as the game draws the player in the inventory: the subject turned by
 * the preview's view, then its decorations. The view model is drawn as the camera sees it, with a perspective of its
 * own, since hands and held items are placed for one.
 */
public final class PreviewRenderer extends PictureInPictureRenderer<PreviewRenderState> {
	/** The camera's field of view for the view model, degrees. */
	private static final float HAND_FOV = 70;
	/** How far the view model is raised, in blocks at arm's length, to sit in the middle of the preview. */
	private static final float HAND_RAISE = 0.35F;

	private final Projection perspective = new Projection();
	private final ProjectionMatrixBuffer perspectiveBuffer = new ProjectionMatrixBuffer("PIP - Myriad preview hands");
	private final ItemStackRenderState item = new ItemStackRenderState();
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Preview");
	/** Logged once: a failing preview fails every frame. */
	private boolean failed;

	/**
	 * What the GUI renders for {@code preview} at (x0, y0)-(x1, y1) in GUI units, clipped to {@code scissor};
	 * {@code unitsToGui} turns the preview's own units (its pan) into GUI units.
	 */
	public static PictureInPictureRenderState state(Preview preview, int x0, int y0, int x1, int y1, float unitsToGui, @Nullable ScreenRectangle scissor) {
		return PreviewRenderState.of(preview, x0, y0, x1, y1, unitsToGui, scissor);
	}

	@Override
	public Class<PreviewRenderState> getRenderStateClass() {
		return PreviewRenderState.class;
	}

	@Override
	protected String getTextureLabel() {
		return "myriad preview";
	}

	@Override
	protected float getTranslateY(int height, int guiScale) {
		return height / 2.0F;
	}

	@Override
	protected void renderToTexture(PreviewRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
		try {
			draw(state, poseStack, collector);
		} catch (RuntimeException e) {
			// A subject the game can't draw out of the world shows nothing rather than taking the frame down.
			if (!failed) LOG.warn("A preview couldn't draw {}", state.subject(), e);
			failed = true;
		}
	}

	private void draw(PreviewRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
		Minecraft mc = Minecraft.getInstance();
		if (state.subject() instanceof Preview.ViewModelSubject) {
			viewModel(state, collector);
			return;
		}
		// The pose is in blocks here, the y axis pointing down the screen: moved, flipped upright, then turned.
		poseStack.translate(state.panX() / state.scale(), state.panY() / state.scale(), 0);
		Quaternionf view = new Quaternionf().rotateZ((float) Math.PI).rotateX((float) Math.toRadians(-state.pitch())).rotateY((float) Math.toRadians(state.yaw()));
		poseStack.mulPose(view);
		// The subject's middle on the pivot (an item model is drawn about its middle already; its box is the cube from 0).
		Entity entity = PreviewSubjects.entity(state.subject());
		if (entity != null) poseStack.translate(-state.box().getCenter().x, -state.box().getCenter().y, -state.box().getCenter().z);
		if (entity != null) {
			mc.gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
			var renderer = mc.getEntityRenderDispatcher().getRenderer(entity);
			EntityRenderState render = renderer.createRenderState(entity, 1.0F);
			render.shadowPieces.clear();
			render.outlineColor = 0;
			render.lightCoords = LightCoordsUtil.FULL_BRIGHT;
			if (render instanceof LivingEntityRenderState living) {
				// Facing you with the view unturned; the preview's turn does the rest.
				living.bodyRot = 180;
				living.yRot = 0;
				living.xRot = 0;
			}
			CameraRenderState camera = new CameraRenderState();
			camera.orientation = view.conjugate(new Quaternionf()).rotateY((float) Math.PI);
			mc.getEntityRenderDispatcher().submit(render, camera, 0, 0, 0, poseStack, collector);
		} else if (state.subject() instanceof Preview.ItemSubject s) {
			mc.gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_3D);
			mc.getItemModelResolver().updateForTopItem(item, s.stack(), ItemDisplayContext.NONE, mc.level, null, 0);
			item.submit(poseStack, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
			poseStack.translate(-0.5, -0.5, -0.5);
		}
		state.shapes().depth.submit(collector, poseStack, MyriadPipelines.QUADS, MyriadPipelines.LINES);
		state.shapes().xray.submit(collector, poseStack, MyriadPipelines.QUADS_XRAY, MyriadPipelines.LINES_XRAY);
	}

	/** Your hands and held items as the camera sees them: in front of it, with a perspective, turned a little by the view. */
	private void viewModel(PreviewRenderState state, SubmitNodeCollector collector) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		int guiScale = Math.max(1, mc.getWindow().getGuiScale());
		float width = (state.x1() - state.x0()) * guiScale, height = (state.y1() - state.y0()) * guiScale;
		perspective.setupPerspective(0.05F, 100F, HAND_FOV, width, height);
		RenderSystem.setProjectionMatrix(perspectiveBuffer.getBuffer(perspective), ProjectionType.PERSPECTIVE);
		mc.gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
		PoseStack pose = new PoseStack();
		// Moved and zoomed as the other subjects: a drag across the preview is about its width at arm's length.
		float reach = 1.0F / Math.min(width, height) * guiScale;
		// Hands sit low in the camera's view; raised to the middle of a preview, which is wider than tall.
		pose.translate(state.panX() * reach, HAND_RAISE - state.panY() * reach, (state.zoom() - 1) * 0.6F);
		pose.mulPose(new Quaternionf().rotateX((float) Math.toRadians(state.pitch() * 0.5F)).rotateY((float) Math.toRadians(state.yaw() * 0.5F)));
		mc.gameRenderer.itemInHandRenderer.submitHandsWithItems(1.0F, pose, collector, mc.player, LightCoordsUtil.FULL_BRIGHT);
	}

	@Override
	public void close() {
		super.close();
		perspectiveBuffer.close();
	}
}
