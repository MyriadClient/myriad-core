package dev.myriadclient.impl.render.preview;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.myriadclient.api.event.events.HighlightEvent;
import dev.myriadclient.api.ui.widget.Preview;
import dev.myriadclient.impl.render.HighlightRenderer;
import dev.myriadclient.impl.render.MyriadPipelines;
import dev.myriadclient.impl.render.PreviewHighlights;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One preview's texture, drawn as the game draws the player in the inventory: the subject turned by the preview's view,
 * then its decorations, then its shader highlights (outlines, glows, fills asked for as in the world). The view model is
 * drawn as you see it, with your field of view, since hands and held items are placed for the camera.
 */
final class PreviewPicture extends PictureInPictureRenderer<PreviewRenderState> {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Preview");

	private final Projection perspective = new Projection();
	private final ProjectionMatrixBuffer perspectiveBuffer = new ProjectionMatrixBuffer("PIP - Myriad preview hands");
	private static final BlockDisplayContext BLOCK_CONTEXT = BlockDisplayContext.create();

	private final ItemStackRenderState item = new ItemStackRenderState();
	private final BlockModelRenderState block = new BlockModelRenderState();
	private @Nullable BlockModelResolver blockResolver;
	private final PreviewHighlights highlights = new PreviewHighlights();
	/** The texture drawn into this frame, for the highlights to go onto once the subject is in it. */
	private @Nullable GpuTextureView target;
	/** Logged once: a failing preview fails every frame. */
	private boolean failed;
	/** When the GUI last drew it (nanoseconds), so PreviewRenderer can let go of it once its preview is gone. */
	long used;

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
		target = RenderSystem.outputColorTextureOverride;
		int guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
		highlights.begin((state.x1() - state.x0()) * guiScale, (state.y1() - state.y0()) * guiScale);
		try {
			draw(state, poseStack, collector);
		} catch (RuntimeException e) {
			// A subject the game can't draw out of the world shows nothing rather than taking the frame down.
			if (!failed) LOG.warn("A preview couldn't draw {}", state.subject(), e);
			failed = true;
		} finally {
			HighlightRenderer.INSTANCE.previewHands(null);
		}
		// The outline pass that follows draws the highlighted silhouettes into the highlights' mask.
		highlights.drawing(true);
	}

	@Override
	protected void blitTexture(PreviewRenderState state, GuiRenderState guiRenderState) {
		if (target != null) {
			highlights.drawing(false);
			highlights.finish(target);
			target = null;
		}
		super.blitTexture(state, guiRenderState);
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
		// The subject's middle on the pivot (a block's box is the cube from 0).
		Entity entity = PreviewSubjects.entity(state.subject());
		if (entity != null) {
			poseStack.translate(-state.box().getCenter().x, -state.box().getCenter().y, -state.box().getCenter().z);
			mc.gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
			var renderer = mc.getEntityRenderDispatcher().getRenderer(entity);
			EntityRenderState render = renderer.createRenderState(entity, 1.0F);
			render.shadowPieces.clear();
			HighlightEvent.EntityHighlight asked = HighlightRenderer.INSTANCE.askPreview(entity);
			render.outlineColor = highlights.add(asked.style(), asked.color());
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
			int outline = highlights.add(state.style(), state.color());
			if (s.stack().getItem() instanceof BlockItem b) {
				// A block as it stands in the world (a hopper's item is a flat icon), chests and shulker boxes included.
				blocks().update(block, b.getBlock().defaultBlockState(), BLOCK_CONTEXT);
			} else block.clear();
			poseStack.translate(-0.5, -0.5, -0.5);
			if (!block.isEmpty()) {
				mc.gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_3D);
				block.submit(poseStack, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, outline);
			} else {
				// An item model is drawn about its middle.
				mc.gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_3D);
				mc.getItemModelResolver().updateForTopItem(item, s.stack(), ItemDisplayContext.NONE, mc.level, null, 0);
				poseStack.pushPose();
				poseStack.translate(0.5, 0.5, 0.5);
				item.submit(poseStack, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, outline);
				poseStack.popPose();
			}
		}
		state.shapes().depth.submit(collector, poseStack, MyriadPipelines.QUADS, MyriadPipelines.LINES);
		state.shapes().xray.submit(collector, poseStack, MyriadPipelines.QUADS_XRAY, MyriadPipelines.LINES_XRAY);
	}

	/** Your hands and held items as you see them: in front of the camera, with your field of view, turned a little by the view. */
	private void viewModel(PreviewRenderState state, SubmitNodeCollector collector) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		int guiScale = Math.max(1, mc.getWindow().getGuiScale());
		float width = (state.x1() - state.x0()) * guiScale, height = (state.y1() - state.y0()) * guiScale;
		// Your field of view, so they're framed as on your screen.
		perspective.setupPerspective(0.05F, 100F, mc.options.fov().get(), width, height);
		RenderSystem.setProjectionMatrix(perspectiveBuffer.getBuffer(perspective), ProjectionType.PERSPECTIVE);
		// Lit as in the world, where the game draws them.
		mc.gameRenderer.lighting().setupFor(Lighting.Entry.LEVEL);
		PoseStack pose = new PoseStack();
		// Moved and zoomed as the other subjects: a drag across the preview is about its width at arm's length.
		float reach = 1.0F / Math.min(width, height) * guiScale;
		pose.translate(state.panX() * reach, -state.panY() * reach, (state.zoom() - 1) * 0.6F);
		pose.mulPose(new Quaternionf().rotateX((float) Math.toRadians(state.pitch() * 0.5F)).rotateY((float) Math.toRadians(state.yaw() * 0.5F)));
		// Each part is asked about as in the world, its highlight going into this preview's.
		HighlightRenderer.INSTANCE.previewHands(highlights);
		mc.gameRenderer.itemInHandRenderer.submitHandsWithItems(1.0F, pose, collector, mc.player, LightCoordsUtil.FULL_BRIGHT);
	}

	private BlockModelResolver blocks() {
		if (blockResolver == null) blockResolver = new BlockModelResolver(Minecraft.getInstance().getModelManager());
		return blockResolver;
	}

	@Override
	public void close() {
		super.close();
		perspectiveBuffer.close();
	}
}
