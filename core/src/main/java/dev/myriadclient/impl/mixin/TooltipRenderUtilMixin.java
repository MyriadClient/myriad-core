package dev.myriadclient.impl.mixin;

import dev.myriadclient.impl.ui.ThemedScreens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every tooltip's box (items, buttons, the recipe book), drawn as the desktop's tooltips are (see ThemedScreens). */
@Mixin(TooltipRenderUtil.class)
public abstract class TooltipRenderUtilMixin {
	@Inject(method = "extractTooltipBackground", at = @At("HEAD"), cancellable = true)
	private static void myriad$background(GuiGraphicsExtractor graphics, int x, int y, int w, int h, @Nullable Identifier style, CallbackInfo ci) {
		if (ThemedScreens.tooltip(graphics, x, y, w, h)) ci.cancel();
	}
}
