package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.EntityRenderEvent;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.UvMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * EntityRenderEvent.Model: a living entity's model submitted again after vanilla's, for chams and wireframes.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	/** EntityRenderEvent.Model: after the model is submitted, so chams and wireframes can submit it again. */
	@WrapOperation(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V"))
	private void myriad$model(SubmitNodeCollector collector, Model<?> model, Object state, PoseStack poseStack, RenderType renderType, int light, int overlay,
							  int color, UvMapping uvMapping, int outlineColor, Operation<Void> original) {
		original.call(collector, model, state, poseStack, renderType, light, overlay, color, uvMapping, outlineColor);
		if (state instanceof LivingEntityRenderState living && Myriad.isReady() && Myriad.events().hasListeners(EntityRenderEvent.Model.class)) {
			Myriad.events().post(new EntityRenderEvent.Model(living, model, poseStack, collector));
		}
	}
}
