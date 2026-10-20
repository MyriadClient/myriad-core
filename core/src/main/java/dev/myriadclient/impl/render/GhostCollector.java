package dev.myriadclient.impl.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Passes an entity's render through to the frame's collector as a see-through copy: every model and model part (skin,
 * armour, cape, elytra) in the translucent entity render type of its own texture, at {@code alpha}; no shadow, name,
 * flames or leash. Held items go through as they are (their render types are chosen deeper in). Outline colours pass
 * through, so a highlighted ghost is traced like a highlighted entity.
 */
class GhostCollector implements OrderedSubmitNodeCollector {
	/** Translucent versions of the render types met, by the original. */
	private static final Map<RenderType, RenderType> TRANSLUCENT = new IdentityHashMap<>();

	private final OrderedSubmitNodeCollector delegate;
	final float alpha;

	GhostCollector(OrderedSubmitNodeCollector delegate, float alpha) {
		this.delegate = delegate;
		this.alpha = alpha;
	}

	/** The frame's collector as a ghost: orders asked of it (layers drawn in an order of their own) come back ghosted too. */
	static SubmitNodeCollector of(SubmitNodeCollector collector, float alpha) {
		return new Root(collector, alpha);
	}

	private static final class Root extends GhostCollector implements SubmitNodeCollector {
		private final SubmitNodeCollector frame;

		Root(SubmitNodeCollector frame, float alpha) {
			super(frame, alpha);
			this.frame = frame;
		}

		@Override
		public OrderedSubmitNodeCollector order(int order) {
			return new GhostCollector(frame.order(order), alpha);
		}
	}

	/** {@code type} drawn translucent: the entity translucent type of its first texture, or itself if it has none. */
	private static RenderType translucent(RenderType type) {
		return TRANSLUCENT.computeIfAbsent(type, t -> {
			Map<String, ? extends RenderSetup.TextureBinding> textures = t.state.textures;
			Identifier texture = textures.isEmpty() ? null : textures.values().iterator().next().location();
			// Keeps its outline: a highlighted ghost draws its shape into the entity mask like any entity.
			return texture == null ? t : RenderTypes.entityTranslucent(texture, true);
		});
	}

	@Override
	public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int lightCoords, int overlayCoords,
								int tintedColor, @Nullable TextureAtlasSprite sprite, int outlineColor,
								ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay) {
		delegate.submitModel(model, state, poseStack, translucent(renderType), lightCoords, overlayCoords, ARGB.multiplyAlpha(tintedColor, alpha), sprite, outlineColor,
			null);
	}

	@Override
	public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
	}

	@Override
	public void submitNameTag(PoseStack poseStack, @Nullable Vec3 nameTagAttachment, int offset, Component name, boolean seeThrough, int lightCoords,
							  CameraRenderState camera) {
	}

	@Override
	public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow, Font.DisplayMode displayMode,
						   int lightCoords, int color, int backgroundColor, int outlineColor) {
	}

	@Override
	public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
	}

	@Override
	public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
	}

	@Override
	public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState, int outlineColor) {
		delegate.submitMovingBlock(poseStack, movingBlockRenderState, outlineColor);
	}

	@Override
	public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts, int[] tintLayers, int lightCoords,
								 int overlayCoords, int outlineColor) {
		delegate.submitBlockModel(poseStack, renderType, parts, tintLayers, lightCoords, overlayCoords, 0);
	}

	@Override
	public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress) {
	}

	@Override
	public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color, float width, boolean afterTerrain) {
		delegate.submitShapeOutline(poseStack, shape, renderType, color, width, afterTerrain);
	}

	@Override
	public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords, int overlayCoords, int outlineColor, int[] tintLayers,
						   List<BakedQuad> quads, ItemStackRenderState.FoilType foilType) {
		delegate.submitItem(poseStack, displayContext, lightCoords, overlayCoords, outlineColor, tintLayers, quads, foilType);
	}

	@Override
	public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
		delegate.submitCustomGeometry(poseStack, renderType, customGeometryRenderer);
	}

	@Override
	public void submitQuadParticleGroup(QuadParticleRenderState particles) {
		delegate.submitQuadParticleGroup(particles);
	}

	@Override
	public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
		delegate.submitGizmoPrimitives(group, camera, onTop);
	}
}
