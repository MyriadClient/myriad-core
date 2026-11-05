package dev.myriadclient.impl.render.title;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.myriadclient.impl.cosmetic.MyriadCape;
import dev.myriadclient.impl.render.UiRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.phys.shapes.Shapes;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Draws the title screen's scene with no world: you in a {@link TitleScene}, built by hand from what the game's renderers
 * take. Your skin, skin layers and cape are your own; there's no entity behind them. The picture is drawn at twice the
 * screen's resolution and scaled down, which smooths the edges the world's own rendering would leave jagged, and which
 * would crawl as the view moves.
 */
final class TitleScenePicture extends PictureInPictureRenderer<TitleSceneState> {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/TitleScene");
	private static final BlockDisplayContext BLOCK_CONTEXT = BlockDisplayContext.create();
	/** A long lens, so what's near the camera doesn't loom over you. */
	private static final float FOV = 18;
	/** How many blocks fit up the picture, the height it's centred on, and how big the scene's blocks are next to you. */
	private static final float SHOW_HIGH = 4.2f, LOOK_AT = 1.9f, BLOCK_SIZE = 0.7f;
	/** The picture's longest side in pixels: past this it's less than doubled, down to the screen's own resolution. */
	private static final int MAX_SIDE = 4096;

	private final Projection perspective = new Projection();
	private final ProjectionMatrixBuffer perspectiveBuffer = new ProjectionMatrixBuffer("PIP - Myriad title scene");
	private final BlockModelRenderState block = new BlockModelRenderState();
	private final AvatarRenderState avatar = new AvatarRenderState();
	private @Nullable BlockModelResolver blockResolver;
	private @Nullable Supplier<PlayerSkin> skin;
	private @Nullable GpuTextureView target;
	private int width, height;
	/** Logged once: a failing scene fails every frame. */
	private boolean failed;

	@Override
	public void prepare(TitleSceneState state, GuiRenderState guiRenderState, FeatureRenderDispatcher dispatcher, int guiScale) {
		int longest = Math.max(state.x1() - state.x0(), state.y1() - state.y0());
		int scale = Math.clamp(MAX_SIDE / longest, guiScale, guiScale * 2);
		width = (state.x1() - state.x0()) * scale;
		height = (state.y1() - state.y0()) * scale;
		super.prepare(state, guiRenderState, dispatcher, scale);
	}

	@Override
	protected void renderToTexture(TitleSceneState state, PoseStack unused, SubmitNodeCollector collector) {
		target = RenderSystem.outputColorTextureOverride;
		// The buttons' frosted backgrounds show this rather than the screen, which holds nothing yet.
		UiRenderer ui = UiRenderer.peek();
		if (ui != null && target != null) ui.blurFrom(target, width, height);
		try {
			draw(state, collector);
		} catch (RuntimeException e) {
			if (!failed) LOG.warn("The title screen's scene couldn't be drawn", e);
			failed = true;
		}
	}

	private void draw(TitleSceneState state, SubmitNodeCollector collector) {
		Minecraft mc = Minecraft.getInstance();
		perspective.setupPerspective(0.5F, 300F, FOV, width, height);
		// Moved across the picture as it's drawn, so you're seen straight on wherever you stand on it.
		Matrix4f moved = new Matrix4f().translation(state.across(), 0, 0).mul(perspective.getMatrix(new Matrix4f()));
		RenderSystem.setProjectionMatrix(perspectiveBuffer.getBuffer(moved), ProjectionType.PERSPECTIVE);
		// Lit from above, as the world is; nothing has set this up yet on the title screen.
		Lighting lighting = mc.gameRenderer.lighting();
		lighting.updateLevel(CardinalLighting.Type.DEFAULT);
		lighting.setupFor(Lighting.Entry.LEVEL);

		float distance = SHOW_HIGH / (2 * (float) Math.tan(Math.toRadians(FOV / 2)));

		PoseStack pose = new PoseStack();
		pose.translate(0, 0, -distance);
		pose.mulPose(Axis.XP.rotationDegrees(state.pitch()));
		pose.mulPose(Axis.YP.rotationDegrees(state.yaw()));
		pose.translate(0, -LOOK_AT, 0);

		if (blockResolver == null) blockResolver = new BlockModelResolver(mc.getModelManager());
		for (TitleScene.Placed b : state.scene().blocks()) {
			blockResolver.update(block, b.state(), BLOCK_CONTEXT);
			if (b.tinted()) {
				block.tintLayers().clear();
				block.tintLayers().add(state.green());
			}
			pose.pushPose();
			// Sized from where you stand, centred on its column as you are on yours.
			pose.translate(0, 1, 0);
			pose.scale(BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE);
			pose.translate(b.x() - 0.5f, b.y() - 1, b.z() - 0.5f);
			block.submit(pose, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
			pose.popPose();
		}

		pose.pushPose();
		pose.translate(0, 1, 0);
		player(mc, state, pose, collector);
		pose.popPose();
	}

	private void player(Minecraft mc, TitleSceneState state, PoseStack pose, SubmitNodeCollector collector) {
		GameProfile profile = mc.getGameProfile();
		if (skin == null) skin = mc.getSkinManager().createLookup(profile, false);
		PlayerSkin look = skin.get();
		avatar.skin = MyriadCape.enabled() ? MyriadCape.dress(look) : look;
		avatar.showHat = mc.options.isModelPartEnabled(PlayerModelPart.HAT);
		avatar.showJacket = mc.options.isModelPartEnabled(PlayerModelPart.JACKET);
		avatar.showLeftPants = mc.options.isModelPartEnabled(PlayerModelPart.LEFT_PANTS_LEG);
		avatar.showRightPants = mc.options.isModelPartEnabled(PlayerModelPart.RIGHT_PANTS_LEG);
		avatar.showLeftSleeve = mc.options.isModelPartEnabled(PlayerModelPart.LEFT_SLEEVE);
		avatar.showRightSleeve = mc.options.isModelPartEnabled(PlayerModelPart.RIGHT_SLEEVE);
		avatar.showCape = mc.options.isModelPartEnabled(PlayerModelPart.CAPE);
		avatar.mainArm = mc.options.mainHand().get();
		avatar.lightCoords = LightCoordsUtil.FULL_BRIGHT;
		avatar.ageInTicks = state.time() * 20;
		// Facing the camera, as the scene's turned; its head on its own.
		avatar.bodyRot = 0;
		avatar.yRot = state.headYaw();
		avatar.xRot = state.headPitch();
		avatar.attackTime = state.swing();
		// A little wind in the cape.
		float t = state.time();
		avatar.capeFlap = 2 + (float) Math.sin(t * 1.3) * 2;
		avatar.capeLean = 4 + (float) Math.sin(t * 0.7) * 2;
		// On the grass under your feet.
		avatar.shadowRadius = 0.5f;
		avatar.shadowPieces.clear();
		avatar.shadowPieces.add(new EntityRenderState.ShadowPiece(-0.5f, 0.01f, -0.5f, Shapes.block(), 0.5f));
		mc.getEntityRenderDispatcher().submit(avatar, new CameraRenderState(), 0, 0, 0, pose, collector);
	}

	@Override
	protected void blitTexture(TitleSceneState state, GuiRenderState guiRenderState) {
		if (target == null) return;
		int a = ARGB.as8BitChannel(state.alpha());
		ScreenRectangle scissor = state.scissorArea();
		guiRenderState.addBlitToCurrentLayer(new BlitRenderState(
			RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
			TextureSetup.singleTexture(target, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)),
			state.pose(), state.x0(), state.y0(), state.x1(), state.y1(), 0, 1, 1, 0,
			// The picture's colours are already multiplied by their alpha, so a fade takes the colour down with it.
			ARGB.color(a, a, a, a), scissor, null));
		target = null;
	}

	@Override
	public Class<TitleSceneState> getRenderStateClass() {
		return TitleSceneState.class;
	}

	@Override
	protected String getTextureLabel() {
		return "myriad title scene";
	}

	@Override
	public void close() {
		super.close();
		perspectiveBuffer.close();
	}
}
