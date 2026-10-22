package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.myriadclient.impl.render.HighlightRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla never runs the outline pass for the hand; with hand highlights it runs last, drawing their silhouettes into
 * the hand's mask (HighlightRenderer points the outline target there meanwhile).
 */
@Mixin(FeatureRenderDispatcher.class)
public abstract class FeatureRenderDispatcherMixin {
	@WrapOperation(method = "renderAllFeatures", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeAlwaysOnTop()V"))
	private void myriad$handOutlines(FeatureRenderDispatcher.PreparedFrame frame, Operation<Void> original) {
		original.call(frame);
		if (HighlightRenderer.INSTANCE.handHighlighted()) frame.executeOutline();
	}
}
