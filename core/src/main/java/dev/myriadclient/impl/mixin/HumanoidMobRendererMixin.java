package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.impl.service.InventoryManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Your own model wears what masks keep the armour slots showing (see Inventory.mask): no flicker of wings and armour. */
@Mixin(HumanoidMobRenderer.class)
public abstract class HumanoidMobRendererMixin {
	@WrapOperation(method = "getEquipmentIfRenderable", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getItemBySlot(Lnet/minecraft/world/entity/EquipmentSlot;)Lnet/minecraft/world/item/ItemStack;"))
	private static ItemStack myriad$shownArmour(LivingEntity entity, EquipmentSlot slot, Operation<ItemStack> original) {
		if (!Myriad.isReady() || entity != Minecraft.getInstance().player) return original.call(entity, slot);
		return ((InventoryManager) Myriad.inventory()).shownEquipment(slot);
	}
}
