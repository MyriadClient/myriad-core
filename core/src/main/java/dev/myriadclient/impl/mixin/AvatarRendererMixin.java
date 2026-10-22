package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.myriadclient.api.event.events.HighlightEvent;
import dev.myriadclient.impl.render.HighlightRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The first-person arm is submitted with its highlight's outline colour, so it's drawn into the hand's mask. */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	private static final Matrix4f IDENTITY = new Matrix4f();

	@WrapOperation(method = "renderHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IILnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V"))
	private void myriad$highlightArm(SubmitNodeCollector collector, ModelPart part, PoseStack poseStack, RenderType renderType, int light, int overlay,
									 TextureAtlasSprite sprite, Operation<Void> original, @Local(argsOnly = true) ModelPart arm) {
		var player = Minecraft.getInstance().player;
		if (!HighlightRenderer.INSTANCE.drawingHand() || player == null) {
			original.call(collector, part, poseStack, renderType, light, overlay, sprite);
			return;
		}
		HumanoidArm side = arm == ((AvatarRenderer<?>) (Object) this).getModel().leftArm ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
		InteractionHand hand = side == player.getMainArm() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
		// The arm's extents come out already in the pose stack's space.
		int outline = HighlightRenderer.INSTANCE.hand(HighlightEvent.Hand.Part.ARM, hand, side, ItemStack.EMPTY, IDENTITY,
			out -> part.getExtentsForGui(poseStack, out));
		if (outline == 0) original.call(collector, part, poseStack, renderType, light, overlay, sprite);
		else collector.submitModelPart(part, poseStack, renderType, light, overlay, sprite, -1, null, outline);
	}
}
