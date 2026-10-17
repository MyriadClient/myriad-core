package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.util.Slots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The hotbar, the selected item's name and the armour bar show what masks and borrows keep them showing (see Inventory.mask). */
@Mixin(Hud.class)
public abstract class HudMixin {
	@WrapOperation(method = "extractItemHotbar", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;getItem(I)Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack myriad$shownSlot(Inventory inventory, int slot, Operation<ItemStack> original) {
		return Myriad.isReady() && slot >= 0 && slot < 9 ? Myriad.inventory().shown(slot) : original.call(inventory, slot);
	}

	@ModifyExpressionValue(method = "extractItemHotbar", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getOffhandItem()Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack myriad$shownOffhand(ItemStack stack) {
		return Myriad.isReady() ? Myriad.inventory().shown(Slots.OFF_HAND) : stack;
	}

	@ModifyExpressionValue(method = "extractArmor", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getArmorValue()I"))
	private static int myriad$shownArmor(int armor) {
		return Myriad.isReady() ? Myriad.inventory().shownArmor() : armor;
	}

	/** Nor does the selected item's name flash up for a masked swap. */
	@ModifyExpressionValue(method = "tick()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;getSelectedItem()Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack myriad$shownSelected(ItemStack stack) {
		var player = Minecraft.getInstance().player;
		return Myriad.isReady() && player != null ? Myriad.inventory().shown(player.getInventory().getSelectedSlot()) : stack;
	}
}
