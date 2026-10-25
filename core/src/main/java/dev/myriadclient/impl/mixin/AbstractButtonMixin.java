package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.myriadclient.impl.ui.ThemedScreens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A button on a screen drawn in the theme (a villager's offers), as a theme button (see ThemedScreens). */
@Mixin(AbstractButton.class)
public abstract class AbstractButtonMixin {
	@WrapOperation(method = "extractDefaultSprite", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V"))
	private void myriad$sprite(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, int argb, Operation<Void> original) {
		if (!ThemedScreens.button(graphics, sprite, x, y, w, h, argb)) original.call(graphics, pipeline, sprite, x, y, w, h, argb);
	}
}
