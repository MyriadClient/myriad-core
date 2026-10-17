package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.impl.service.InventoryManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Your own model holds what masks keep the hands showing (see Inventory.mask). */
@Mixin(ArmedEntityRenderState.class)
public abstract class ArmedEntityRenderStateMixin {
	@WrapOperation(method = "extractArmedEntityRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getItemHeldByArm(Lnet/minecraft/world/entity/HumanoidArm;)Lnet/minecraft/world/item/ItemStack;"))
	private static ItemStack myriad$shownHeld(LivingEntity entity, HumanoidArm arm, Operation<ItemStack> original) {
		if (!Myriad.isReady() || entity != Minecraft.getInstance().player) return original.call(entity, arm);
		return ((InventoryManager) Myriad.inventory()).shownEquipment(arm == entity.getMainArm() ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
	}
}
