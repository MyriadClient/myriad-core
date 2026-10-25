package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.myriadclient.impl.ui.ThemedScreens;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** An enchanting table's choices and their levels, and their text's colours, in the theme (see ThemedScreens). */
@Mixin(targets = "net.minecraft.client.gui.screens.inventory.EnchantmentScreen")
public abstract class EnchantmentScreenMixin {
	@WrapOperation(method = {"extractBackground"}, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
	private void myriad$sprite(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, Operation<Void> original) {
		if (!ThemedScreens.sprite(graphics, sprite, x, y, w, h)) original.call(graphics, pipeline, sprite, x, y, w, h);
	}

	@WrapOperation(method = "extractBackground", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;textWithWordWrap(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/FormattedText;IIIIZ)V"))
	private void myriad$words(GuiGraphicsExtractor graphics, Font font, FormattedText text, int x, int y, int width, int color, boolean shadow, Operation<Void> original) {
		original.call(graphics, font, text, x, y, width, ThemedScreens.textColor(color), shadow);
	}

	@WrapOperation(method = "extractBackground", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"))
	private void myriad$cost(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color, Operation<Void> original) {
		original.call(graphics, font, text, x, y, ThemedScreens.textColor(color));
	}
}
