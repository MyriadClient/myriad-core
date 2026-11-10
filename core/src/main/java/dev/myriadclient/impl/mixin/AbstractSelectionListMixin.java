package dev.myriadclient.impl.mixin;

import dev.myriadclient.impl.ui.ThemedMenus;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Themed Screens: a list's edges fade into the theme's background where its separators were (ThemedMenus.listEdges). */
@Mixin(AbstractSelectionList.class)
public abstract class AbstractSelectionListMixin {
	@Inject(method = "extractListSeparators", at = @At("HEAD"), cancellable = true)
	private void myriad$fadedEdges(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		AbstractSelectionList<?> list = (AbstractSelectionList<?>) (Object) this;
		if (ThemedMenus.listEdges(graphics, list.getX(), list.getY(), list.getWidth(), list.getBottom(), list.scrollAmount(), list.maxScrollAmount())) ci.cancel();
	}
}
