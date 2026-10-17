package dev.myriadclient.impl.mixin;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.CameraEvent;
import dev.myriadclient.impl.render.HighlightRenderer;
import dev.myriadclient.impl.render.WorldRenderQueue;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void myriad$hand(CameraRenderState cameraState, float deltaPartialTick, Matrix4fc modelViewMatrix, CallbackInfo ci) {
		if (Myriad.isReady() && Myriad.events().hasListeners(CameraEvent.Hand.class)
			&& Myriad.events().post(new CameraEvent.Hand()).isCancelled()) ci.cancel();
	}

	/** Vanilla multiplies the projection by the view bob; tracers undo it to start at the crosshair. */
	@ModifyArg(method = "renderLevel", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;mul(Lorg/joml/Matrix4fc;)Lorg/joml/Matrix4f;", ordinal = 0))
	private Matrix4fc myriad$viewBob(Matrix4fc bob) {
		WorldRenderQueue.INSTANCE.viewBob(bob);
		return bob;
	}

	/** The level projection as drawn (view bobbing and nausea included), so highlights know where things land on screen. */
	@ModifyArg(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
	private Matrix4f myriad$levelProjection(Matrix4f projection) {
		HighlightRenderer.INSTANCE.projection(projection);
		return projection;
	}

	/** Highlights go over the finished world and under the hand, while the world's depth is still there. */
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V", shift = At.Shift.AFTER))
	private void myriad$highlights(DeltaTracker deltaTracker, CallbackInfo ci) {
		HighlightRenderer.INSTANCE.composite();
	}
}
