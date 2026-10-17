package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RenderPipelines.class)
public abstract class RenderPipelinesMixin {
	/**
	 * Vanilla draws entity outlines (the glowing effect's silhouettes) without depth, so whichever entity is drawn last
	 * wins where two overlap, and the target's depth stays empty. Highlights need the nearest silhouette per pixel and
	 * its depth (to tell what the world hides), so the outline shaders get the standard depth test; the target already
	 * has a depth buffer that vanilla clears every frame. The glowing effect looks the same.
	 */
	@WrapOperation(method = "<clinit>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;withVertexShader(Ljava/lang/String;)Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;"))
	private static RenderPipeline.Builder myriad$outlineDepth(RenderPipeline.Builder builder, String shader, Operation<RenderPipeline.Builder> original) {
		RenderPipeline.Builder result = original.call(builder, shader);
		return "core/rendertype_outline".equals(shader) ? result.withDepthStencilState(DepthStencilState.DEFAULT) : result;
	}
}
