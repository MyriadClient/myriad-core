package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.HighlightEvent;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.render.HighlightRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * First-person hands show what masks keep them showing (see Inventory.mask), without dipping for a masked swap, and
 * held items are submitted with their highlight's outline colour (HighlightEvent.Hand).
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getMainHandItem()Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack myriad$shownMainHand(ItemStack stack) {
		return Myriad.isReady() ? MyriadImpl.get().inventoryManager().shownEquipment(EquipmentSlot.MAINHAND) : stack;
	}

	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getOffhandItem()Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack myriad$shownOffHand(ItemStack stack) {
		return Myriad.isReady() ? MyriadImpl.get().inventoryManager().shownEquipment(EquipmentSlot.OFFHAND) : stack;
	}

	/** A new item in the hand restarts the swap timer; a masked swap isn't one. */
	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getItemSwapScale(F)F"))
	private float myriad$steadySwapScale(float scale) {
		var player = Minecraft.getInstance().player;
		return Myriad.isReady() && player != null && MyriadImpl.get().inventoryManager().masked(player.getInventory().getSelectedSlot()) ? 1 : scale;
	}

	@WrapOperation(method = "renderItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
	private void myriad$highlightItem(ItemStackRenderState state, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, int outline,
									  Operation<Void> original, @Local(argsOnly = true) LivingEntity mob, @Local(argsOnly = true) ItemStack stack,
									  @Local(argsOnly = true) ItemDisplayContext context) {
		var player = Minecraft.getInstance().player;
		if (HighlightRenderer.INSTANCE.drawingHand() && mob == player && context.firstPerson()) {
			HumanoidArm side = context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
			InteractionHand hand = side == player.getMainArm() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
			int highlight = HighlightRenderer.INSTANCE.hand(HighlightEvent.Hand.Part.ITEM, hand, side, stack, poseStack.last().pose(), state::visitExtents);
			if (highlight != 0) outline = highlight;
		}
		original.call(state, poseStack, collector, light, overlay, outline);
	}
}
