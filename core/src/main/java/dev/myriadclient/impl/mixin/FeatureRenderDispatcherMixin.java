package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.myriadclient.impl.render.HandHighlights;
import dev.myriadclient.impl.render.PreviewHighlights;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla never runs the outline pass for the hand, or for what's drawn into a picture in the GUI; with hand highlights,
 * or a preview's, it runs last, drawing their silhouettes into the hand's or the preview's mask (the outline target
 * points there meanwhile).
 */
@Mixin(FeatureRenderDispatcher.class)
public abstract class FeatureRenderDispatcherMixin {
	@WrapOperation(method = "renderAllFeatures", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeAlwaysOnTop()V"))
	private void myriad$handOutlines(FeatureRenderDispatcher.PreparedFrame frame, Operation<Void> original) {
		original.call(frame);
		if (HandHighlights.INSTANCE.handHighlighted()) frame.executeOutline();
		else if (PreviewHighlights.drawing() != null) {
			// A preview draws into its own texture (the output override): its outline pass goes to its mask instead.
			GpuTextureView color = RenderSystem.outputColorTextureOverride, depth = RenderSystem.outputDepthTextureOverride;
			RenderSystem.outputColorTextureOverride = null;
			RenderSystem.outputDepthTextureOverride = null;
			try {
				frame.executeOutline();
			} finally {
				RenderSystem.outputColorTextureOverride = color;
				RenderSystem.outputDepthTextureOverride = depth;
			}
		}
	}
}
