package dev.myriadclient.impl.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.myriadclient.impl.ui.ThemedMenus;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Themed Screens: Minecraft's widget sprites and screen backgrounds, wherever they're drawn on a screen, in the theme (see ThemedMenus). */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsExtractorMixin {
	@Inject(method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V", at = @At("HEAD"), cancellable = true)
	private void myriad$themedSprite(RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, int color, CallbackInfo ci) {
		if (ThemedMenus.sprite((GuiGraphicsExtractor) (Object) this, sprite, x, y, w, h, color)) ci.cancel();
	}

	@Inject(method = "blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V", at = @At("HEAD"), cancellable = true)
	private void myriad$themedTexture(RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v, int w, int h, int srcW, int srcH, int texW, int texH,
	                                  int color, CallbackInfo ci) {
		if (ThemedMenus.texture((GuiGraphicsExtractor) (Object) this, texture, x, y, w, h, color)) ci.cancel();
	}
}
