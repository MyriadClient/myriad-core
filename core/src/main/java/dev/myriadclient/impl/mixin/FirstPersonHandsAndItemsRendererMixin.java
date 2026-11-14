package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.myriadclient.api.event.events.HighlightEvent;
import dev.myriadclient.impl.render.HandHighlights;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** First-person held items are submitted with their highlight's outline colour (HighlightEvent.Hand). */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsAndItemsRendererMixin {
	@WrapOperation(method = "submitArmWithItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
	private void myriad$highlightItem(ItemStackRenderState state, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, int outline,
									  Operation<Void> original, @Local(argsOnly = true) InteractionHand hand, @Local(argsOnly = true) ItemStack stack) {
		var player = Minecraft.getInstance().player;
		if (HandHighlights.INSTANCE.drawingHand() && player != null) {
			HumanoidArm side = hand.asArm(player.getMainArm());
			int highlight = HandHighlights.INSTANCE.hand(HighlightEvent.Hand.Part.ITEM, hand, side, stack, poseStack.last().pose(), state::visitExtents);
			if (highlight != 0) outline = highlight;
		}
		original.call(state, poseStack, collector, light, overlay, outline);
	}
}
