package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.myriadclient.impl.ui.ThemedScreens;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The inventory's own label (Crafting), drawn by the themed window instead (see ThemedScreens). */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {
	@WrapOperation(method = "extractLabels", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"))
	private void myriad$label(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int color, boolean shadow, Operation<Void> original) {
		if (!ThemedScreens.themed((AbstractContainerScreen<?>) (Object) this)) original.call(graphics, font, text, x, y, color, shadow);
	}
}
