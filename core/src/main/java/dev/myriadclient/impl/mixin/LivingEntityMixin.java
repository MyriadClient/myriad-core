package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.GlideEvent;
import dev.myriadclient.api.event.events.ItemUseEvent;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.elytra.ElytraManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	/** The local player finished eating or drinking. */
	@Inject(method = "completeUsingItem", at = @At("HEAD"))
	private void myriad$finishUsing(CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self != Minecraft.getInstance().player || !Myriad.isReady() || self.getUseItem().isEmpty()) return;
		if (Myriad.events().hasListeners(ItemUseEvent.Finished.class)) Myriad.events().post(new ItemUseEvent.Finished(self.getUseItem().copy()));
	}

	/** Rotation move fix, or a pilot steering: a sprint jump pushes along the yaw being sent to the server. */
	@ModifyExpressionValue(method = "jumpFromGround", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getYRot()F"))
	private float myriad$jumpYaw(float original) {
		if ((Object) this != Minecraft.getInstance().player || MyriadImpl.get() == null) return original;
		if (ElytraManager.steering()) return Myriad.rotations().rotationForAction()[0];
		float yaw = MyriadImpl.get().rotationManager().movementYaw();
		return Float.isNaN(yaw) ? original : yaw;
	}

	/** A pilot's hold, or No Durability's breaks: the local player keeps gliding though the elytra closed. */
	@ModifyReturnValue(method = "isFallFlying", at = @At("RETURN"))
	private boolean myriad$holdGlide(boolean original) {
		return original || (Object) this == Minecraft.getInstance().player && ElytraManager.holdsGlide();
	}

	/** A pilot steering: the glide follows the rotation being sent, with vanilla's own look maths (what Grim simulates). */
	@ModifyExpressionValue(method = "updateFallFlyingMovement", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getLookAngle()Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 myriad$glideLook(Vec3 original) {
		if ((Object) this != Minecraft.getInstance().player || !ElytraManager.steering()) return original;
		float[] r = Myriad.rotations().rotationForAction();
		return ((LivingEntity) (Object) this).calculateViewVector(r[1], r[0]);
	}

	@ModifyExpressionValue(method = "updateFallFlyingMovement", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getXRot()F"))
	private float myriad$glidePitch(float original) {
		return (Object) this == Minecraft.getInstance().player && ElytraManager.steering() ? Myriad.rotations().rotationForAction()[1] : original;
	}

	/** The glide's movement this tick: none while No Durability holds you still, else whatever GlideEvent says. */
	@ModifyArg(method = "travelFallFlying", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"), index = 1)
	private Vec3 myriad$glideMovement(Vec3 movement) {
		if ((Object) this != Minecraft.getInstance().player || !Myriad.isReady()) return movement;
		if (ElytraManager.holdsStill()) return Vec3.ZERO;
		if (!Myriad.events().hasListeners(GlideEvent.class)) return movement;
		GlideEvent e = Myriad.events().post(new GlideEvent(movement));
		if (e.setsVelocity()) ((LivingEntity) (Object) this).setDeltaMovement(e.movement());
		return e.movement();
	}
}
