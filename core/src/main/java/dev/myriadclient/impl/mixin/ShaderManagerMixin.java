package dev.myriadclient.impl.mixin;

import dev.myriadclient.impl.render.HighlightRenderer;
import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rendering and highlights: tells HighlightRenderer when shaders have been reloaded with the resources, so it rebuilds
 * what it made from them.
 */
@Mixin(ShaderManager.class)
public abstract class ShaderManagerMixin {
	@Inject(method = "apply", at = @At("TAIL"))
	private void myriad$reloaded(CallbackInfo ci) {
		HighlightRenderer.INSTANCE.resourcesReloaded();
	}
}
