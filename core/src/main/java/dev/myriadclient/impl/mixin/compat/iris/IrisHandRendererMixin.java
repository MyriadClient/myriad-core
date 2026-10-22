package dev.myriadclient.impl.mixin.compat.iris;

import dev.myriadclient.impl.render.HighlightRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * With a shader pack on, Iris draws the hand inside the level, in a solid and a translucent pass of its own, and skips
 * vanilla's: hand highlights are asked about in those passes (with Iris's hand projection), and composited where
 * vanilla's hand would be drawn (GameRendererMixin), over the pack's finished image.
 */
@Mixin(targets = "net.irisshaders.iris.pathways.HandRenderer", remap = false)
public abstract class IrisHandRendererMixin {
	@ModifyArg(method = "setupGlState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
	private Matrix4f myriad$handProjection(Matrix4f projection) {
		if (HighlightRenderer.INSTANCE.isArmed()) HighlightRenderer.INSTANCE.beginHand(projection);
		return projection;
	}

	@Inject(method = {"renderSolid", "renderTranslucent"}, at = @At("RETURN"))
	private void myriad$handPassDone(CallbackInfo ci) {
		HighlightRenderer.INSTANCE.pauseHand();
	}
}
