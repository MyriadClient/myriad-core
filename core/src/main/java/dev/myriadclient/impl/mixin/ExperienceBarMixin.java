package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.myriadclient.impl.ui.ThemedHud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.contextualbar.ExperienceBar;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The experience bar's track and fill, drawn in the theme while it's on (see ThemedHud). */
@Mixin(ExperienceBar.class)
public abstract class ExperienceBarMixin {
	@WrapOperation(method = "extractBackground", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
	private void myriad$track(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, Operation<Void> original) {
		if (!ThemedHud.experienceTrack(graphics, x, y, w, h)) original.call(graphics, pipeline, sprite, x, y, w, h);
	}

	@WrapOperation(method = "extractBackground", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIIIII)V"))
	private void myriad$fill(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int textureW, int textureH, int u, int v, int x, int y, int w, int h,
	                         Operation<Void> original) {
		if (!ThemedHud.experienceFill(graphics, x, y, w, h)) original.call(graphics, pipeline, sprite, textureW, textureH, u, v, x, y, w, h);
	}
}
