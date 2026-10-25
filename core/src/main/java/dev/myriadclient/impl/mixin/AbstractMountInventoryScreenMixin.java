package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.myriadclient.impl.ui.ThemedScreens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A horse's or llama's slot sprites, under the theme's cells (see ThemedScreens). */
@Mixin(targets = "net.minecraft.client.gui.screens.inventory.AbstractMountInventoryScreen")
public abstract class AbstractMountInventoryScreenMixin {
	@WrapOperation(method = "extractSlot", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
	private void myriad$sprite(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, Operation<Void> original) {
		if (!ThemedScreens.sprite(graphics, sprite, x, y, w, h)) original.call(graphics, pipeline, sprite, x, y, w, h);
	}

	@WrapOperation(method = {"extractBackground"}, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIIIII)V"))
	private void myriad$progress(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int textureW, int textureH, int u, int v, int x, int y, int w, int h,
	                              Operation<Void> original) {
		if (!ThemedScreens.spritePart(graphics, sprite, textureW, textureH, u, v, x, y, w, h)) original.call(graphics, pipeline, sprite, textureW, textureH, u, v, x, y, w, h);
	}
}
