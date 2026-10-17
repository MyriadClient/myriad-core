package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.util.ItemInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While a module holds a hotbar slot (Inventory.hold), the server mines with that slot's item, so the client must too:
 * otherwise breaking progress runs at the visible item's speed and a held pickaxe mines like a bare hand.
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
	private boolean myriad$holding() {
		return (Object) this == Minecraft.getInstance().player && Myriad.isReady() && Myriad.inventory().isHolding();
	}

	@ModifyExpressionValue(method = {"getDestroySpeed", "hasCorrectToolForDrops"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;getSelectedItem()Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack myriad$heldTool(ItemStack original) {
		return myriad$holding() ? Myriad.inventory().serverItem() : original;
	}

	/** Efficiency is an attribute of the visible item; swap its share for the held item's. */
	@ModifyExpressionValue(method = "getDestroySpeed",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getAttributeValue(Lnet/minecraft/core/Holder;)D", ordinal = 0))
	private double myriad$heldEfficiency(double original) {
		if (!myriad$holding()) return original;
		Player self = (Player) (Object) this;
		return original - efficiency(self.getInventory().getSelectedItem()) + efficiency(Myriad.inventory().serverItem());
	}

	/** Vanilla's Efficiency bonus: level² + 1. */
	private static double efficiency(ItemStack stack) {
		int level = ItemInfo.enchantmentLevel(stack, Enchantments.EFFICIENCY);
		return level > 0 ? level * level + 1 : 0;
	}
}
