package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.myriadclient.impl.ui.ThemedText;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** An item's count in the theme's font while it's on (see ThemedText), measured in it so it stays right-aligned. */
@Mixin(GuiGraphicsExtractor.class)
public abstract class ItemCountMixin {
	@WrapOperation(method = "itemCount", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;width(Ljava/lang/String;)I"))
	private int myriad$width(Font font, String text, Operation<Integer> original) {
		return ThemedText.on() ? font.width(ThemedText.styled(Component.literal(text))) : original.call(font, text);
	}

	@WrapOperation(method = "itemCount", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V"))
	private void myriad$text(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color, boolean shadow, Operation<Void> original) {
		if (ThemedText.on()) graphics.text(font, ThemedText.styled(Component.literal(text)), x, y, color, shadow);
		else original.call(graphics, font, text, x, y, color, shadow);
	}
}
