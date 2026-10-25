package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.myriadclient.impl.ui.ThemedScreens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An anvil's name box (centred) and its can't-do mark, in the theme (see ThemedScreens). */
@Mixin(AnvilScreen.class)
public abstract class AnvilScreenMixin {
	@Shadow
	private EditBox name;

	/** Its name box centred, where the theme draws it (see ThemedScreens#ANVIL_NAME_SHIFT). */
	@Inject(method = "subInit", at = @At("TAIL"))
	private void myriad$centreName(CallbackInfo ci) {
		if (ThemedScreens.themed((AbstractContainerScreen<?>) (Object) this)) name.setX(name.getX() - ThemedScreens.ANVIL_NAME_SHIFT);
	}

	@WrapOperation(method = {"extractBackground", "extractErrorIcon"}, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
	private void myriad$sprite(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, Operation<Void> original) {
		if (!ThemedScreens.sprite(graphics, sprite, x, y, w, h)) original.call(graphics, pipeline, sprite, x, y, w, h);
	}
}
