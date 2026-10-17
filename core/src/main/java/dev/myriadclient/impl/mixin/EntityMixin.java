package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.PlayerViewEvent;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.elytra.ElytraManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets modules move the local player's view ray (only the local player, and only when something listens). */
@Mixin(Entity.class)
public abstract class EntityMixin {
	@Inject(method = "getEyePosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At("RETURN"), cancellable = true)
	private void myriad$eyes(float tickDelta, CallbackInfoReturnable<Vec3> cir) {
		if ((Object) this != Minecraft.getInstance().player || !Myriad.isReady() || !Myriad.events().hasListeners(PlayerViewEvent.Eyes.class)) return;
		cir.setReturnValue(Myriad.events().post(new PlayerViewEvent.Eyes(cir.getReturnValue(), tickDelta)).value);
	}

	@Inject(method = "getViewVector", at = @At("RETURN"), cancellable = true)
	private void myriad$look(float tickDelta, CallbackInfoReturnable<Vec3> cir) {
		if ((Object) this != Minecraft.getInstance().player || !Myriad.isReady() || !Myriad.events().hasListeners(PlayerViewEvent.Look.class)) return;
		cir.setReturnValue(Myriad.events().post(new PlayerViewEvent.Look(cir.getReturnValue(), tickDelta)).value);
	}

	/** Rotation move fix, or a pilot steering: the local player walks, swims and flies with the yaw being sent to the server. */
	@ModifyExpressionValue(method = "moveRelative", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getYRot()F"))
	private float myriad$movementYaw(float original) {
		if ((Object) this != Minecraft.getInstance().player || MyriadImpl.get() == null) return original;
		if (ElytraManager.steering()) return Myriad.rotations().rotationForAction()[0];
		float yaw = MyriadImpl.get().rotationManager().movementYaw();
		return Float.isNaN(yaw) ? original : yaw;
	}
}
