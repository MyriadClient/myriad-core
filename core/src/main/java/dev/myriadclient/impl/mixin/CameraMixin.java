package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.CameraEvent;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
	private void myriad$rotation(Camera camera, float yaw, float pitch, Operation<Void> original, @Local(argsOnly = true) float tickDelta) {
		if (Myriad.isReady() && Myriad.events().hasListeners(CameraEvent.Rotation.class)) {
			CameraEvent.Rotation e = Myriad.events().post(new CameraEvent.Rotation(yaw, pitch, tickDelta));
			yaw = e.yaw;
			pitch = e.pitch;
		}
		original.call(camera, yaw, pitch);
	}

	@WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V"))
	private void myriad$position(Camera camera, double x, double y, double z, Operation<Void> original, @Local(argsOnly = true) float tickDelta) {
		if (Myriad.isReady() && Myriad.events().hasListeners(CameraEvent.Position.class)) {
			CameraEvent.Position e = Myriad.events().post(new CameraEvent.Position(x, y, z, tickDelta));
			x = e.x;
			y = e.y;
			z = e.z;
		}
		original.call(camera, x, y, z);
	}

	/** Riding a minecart that interpolates its own position: the same event, from a vector. */
	@WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(Lnet/minecraft/world/phys/Vec3;)V"))
	private void myriad$positionVec(Camera camera, Vec3 pos, Operation<Void> original, @Local(argsOnly = true) float tickDelta) {
		if (Myriad.isReady() && Myriad.events().hasListeners(CameraEvent.Position.class)) {
			CameraEvent.Position e = Myriad.events().post(new CameraEvent.Position(pos.x, pos.y, pos.z, tickDelta));
			pos = new Vec3(e.x, e.y, e.z);
		}
		original.call(camera, pos);
	}
}
