package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.myriadclient.impl.elytra.ElytraManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A rocket boosts the local player only while the glide goes on as the server sees it: not once it has touched the
 * ground (the server ended it there; the client hears a round trip later), where the boost lifted you off into a glide
 * that then dropped in mid-air.
 */
@Mixin(FireworkRocketEntity.class)
public abstract class FireworkRocketEntityMixin {
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isFallFlying()Z"))
	private boolean myriad$boostsGlide(LivingEntity entity, Operation<Boolean> original) {
		boolean gliding = original.call(entity);
		return gliding && !(entity == Minecraft.getInstance().player && ElytraManager.landedInGlide());
	}
}
