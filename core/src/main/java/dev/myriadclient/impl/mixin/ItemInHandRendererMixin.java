package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.impl.MyriadImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** First-person hands show what masks keep them showing (see Inventory.mask), without dipping for a masked swap. */
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
}
