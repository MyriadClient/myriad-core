package dev.myriadclient.impl.mixin;

import dev.myriadclient.impl.ui.MenuButton;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** The pause screen with Myriad's button at the end of its row of icon buttons (bugs, feedback, friends, reporting). */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin {
	@ModifyArg(method = "createPauseMenu", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;ILnet/minecraft/client/gui/layouts/LayoutSettings;)Lnet/minecraft/client/gui/layouts/LayoutElement;"),
		index = 0)
	private LayoutElement myriad$menuButton(LayoutElement element) {
		// The row is the menu's only horizontal layout.
		if (element instanceof LinearLayout row) row.addChild(MenuButton.create());
		return element;
	}
}
