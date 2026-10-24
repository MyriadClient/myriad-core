package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.myriadclient.impl.render.GpuTimings;
import dev.myriadclient.impl.render.HandHighlights;
import dev.myriadclient.impl.render.HighlightRenderer;
import dev.myriadclient.impl.render.PreviewHighlights;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Highlights (HighlightRenderer) take over vanilla's entity outline target in frames that have any, and the hand's outline pass. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Shadow
	@Final
	private LevelRenderState levelRenderState;

	@Inject(method = "render", at = @At("HEAD"))
	private void myriad$highlightCamera(GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker, boolean renderOutline, CameraRenderState cameraState,
										Matrix4fc modelViewMatrix, GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, CallbackInfo ci) {
		HighlightRenderer.INSTANCE.camera(cameraState.pos, modelViewMatrix, cameraState.cullFrustum, levelRenderState.shouldShowEntityOutlines);
	}

	/** The outline target holds highlight ids, not colours: skip vanilla's glow post effect on it. */
	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;hasAnyOutline()Z"))
	private boolean myriad$vanillaGlow(FeatureRenderDispatcher.PreparedFrame frame, Operation<Boolean> original) {
		return original.call(frame) && !HighlightRenderer.INSTANCE.ownsOutlines();
	}

	/** For .bench render: the outline pass, which draws highlighted silhouettes into the masks. */
	@Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeOutline()V"))
	private void myriad$beforeOutlines(CallbackInfo ci) {
		GpuTimings.begin(GpuTimings.Pass.MASKS);
	}

	/** Once the silhouettes are drawn, before translucent terrain (so water doesn't count as hiding what's under it). */
	@Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeOutline()V", shift = At.Shift.AFTER))
	private void myriad$afterOutlines(CallbackInfo ci) {
		HighlightRenderer.INSTANCE.afterMask();
	}

	/**
	 * While the hand is drawn with highlights, the outline target is the hand's mask; while a preview is drawn, the
	 * preview's (see FeatureRenderDispatcherMixin).
	 */
	@ModifyReturnValue(method = "entityOutlineTarget", at = @At("RETURN"))
	private RenderTarget myriad$handMask(RenderTarget target) {
		PreviewHighlights preview = PreviewHighlights.drawing();
		if (preview != null && preview.mask() != null) return preview.mask();
		return HandHighlights.INSTANCE.handHighlighted() ? HandHighlights.INSTANCE.handMask() : target;
	}

	/** Vanilla blits its processed glow onto the screen here; highlights draw themselves instead. */
	@Inject(method = "doEntityOutline", at = @At("HEAD"), cancellable = true)
	private void myriad$doEntityOutline(CallbackInfo ci) {
		if (HighlightRenderer.INSTANCE.ownsOutlines()) ci.cancel();
	}
}
