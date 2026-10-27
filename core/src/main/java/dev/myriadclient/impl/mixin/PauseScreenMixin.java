package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.myriadclient.impl.ui.MenuButton;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The pause screen with a Myriad button under Back to Game, as wide as it. */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin {
	@WrapOperation(method = "createPauseMenu", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;ILnet/minecraft/client/gui/layouts/LayoutSettings;)Lnet/minecraft/client/gui/layouts/LayoutElement;"))
	private LayoutElement myriad$menuButton(GridLayout.RowHelper helper, LayoutElement backToGame, int columns, LayoutSettings settings, Operation<LayoutElement> original) {
		LayoutElement added = original.call(helper, backToGame, columns, settings);
		helper.addChild(MenuButton.create(204), 2);
		return added;
	}
}
