package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.myriadclient.impl.ui.ThemedScreens;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A villager's offers: arrows, sold-out marks, discounts, scroll thumb and experience bar, and its labels, in the theme (see ThemedScreens). */
@Mixin(targets = "net.minecraft.client.gui.screens.inventory.MerchantScreen")
public abstract class MerchantScreenMixin {
	@WrapOperation(method = {"extractBackground", "extractProgressBar", "extractScroller", "extractButtonArrows", "extractAndDecorateCostA"}, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
	private void myriad$sprite(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, Operation<Void> original) {
		if (!ThemedScreens.sprite(graphics, sprite, x, y, w, h)) original.call(graphics, pipeline, sprite, x, y, w, h);
	}

	@WrapOperation(method = {"extractProgressBar"}, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIIIII)V"))
	private void myriad$progress(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int textureW, int textureH, int u, int v, int x, int y, int w, int h,
	                              Operation<Void> original) {
		if (!ThemedScreens.spritePart(graphics, sprite, textureW, textureH, u, v, x, y, w, h)) original.call(graphics, pipeline, sprite, textureW, textureH, u, v, x, y, w, h);
	}

	@WrapOperation(method = "extractLabels", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"))
	private void myriad$label(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int color, boolean shadow, Operation<Void> original) {
		original.call(graphics, font, text, x, y, ThemedScreens.textColor(color), shadow);
	}
}
