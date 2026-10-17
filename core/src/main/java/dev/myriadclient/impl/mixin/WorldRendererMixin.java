package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.CameraEvent;
import dev.myriadclient.impl.render.HighlightRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class WorldRendererMixin {
	/** A new frame of highlights starts before any entity is extracted. */
	@Inject(method = "extract", at = @At("HEAD"))
	private void myriad$beginHighlights(DeltaTracker deltaTracker, Camera camera, float deltaPartialTick, CallbackInfo ci) {
		HighlightRenderer.INSTANCE.beginFrame();
	}

	/** Draw the local player when a module has moved the camera out of its head. */
	@WrapOperation(method = "extractVisibleEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;isDetached()Z"))
	private boolean myriad$detached(Camera camera, Operation<Boolean> original) {
		boolean third = original.call(camera);
		if (!Myriad.isReady() || !Myriad.events().hasListeners(CameraEvent.Detached.class)) return third;
		return Myriad.events().post(new CameraEvent.Detached(third)).renderSelf;
	}
}
