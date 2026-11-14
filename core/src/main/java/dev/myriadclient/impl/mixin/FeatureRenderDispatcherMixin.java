package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.myriadclient.impl.render.HandHighlights;
import dev.myriadclient.impl.render.MyriadPipelines;
import dev.myriadclient.impl.render.PreviewHighlights;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Vanilla never runs the outline pass for the hand, or for what's drawn into a picture in the GUI; with hand highlights,
 * or a preview's, it runs as soon as the frame is prepared, drawing their silhouettes into the hand's or the preview's
 * mask.
 */
@Mixin(FeatureRenderDispatcher.class)
public abstract class FeatureRenderDispatcherMixin {
	@ModifyReturnValue(method = "prepareFrame", at = @At("RETURN"))
	private FeatureRenderDispatcher.PreparedFrame myriad$outlines(FeatureRenderDispatcher.PreparedFrame frame, SubmitNodeStorage storage) {
		PreviewHighlights preview = PreviewHighlights.drawing();
		RenderTarget mask = preview != null ? preview.mask() : HandHighlights.INSTANCE.handHighlighted() ? HandHighlights.INSTANCE.handMask() : null;
		if (mask == null) return frame;
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
			.createRenderPass(() -> "Myriad highlight outlines", mask.getColorTextureView(), Optional.empty(), mask.getDepthTextureView(), OptionalDouble.empty())) {
			RenderSystem.bindDefaultUniforms(pass);
			MyriadPipelines.withOutlineDepth(() -> frame.executeOutline(pass));
		}
		return frame;
	}
}
