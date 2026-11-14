package dev.myriadclient.impl.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.myriadclient.impl.render.MyriadPipelines;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Rendering and highlights: entity outlines drawn in the outline passes Myriad gives a depth buffer use depth-tested
 * copies of vanilla's outline pipelines (see {@link MyriadPipelines#OUTLINE_CULL_DEPTH}); everywhere else they draw as
 * vanilla has them.
 */
@Mixin(PreparedRenderType.class)
public abstract class PreparedRenderTypeMixin {
	@ModifyVariable(method = "draw", at = @At("HEAD"), argsOnly = true)
	private RenderPipeline myriad$outlineDepth(RenderPipeline pipeline) {
		return MyriadPipelines.inPass(pipeline);
	}
}
