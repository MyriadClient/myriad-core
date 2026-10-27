package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.myriadclient.impl.ui.MenuButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Minecraft's title screen (Myriad's own off: Theme → Preferences → Title Screen) with a Myriad button under its
 * Realms one, as wide as it: what comes after (the icon buttons, Options and Quit) moves down a row to make room.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
	/** Minecraft's rows are 24 apart. */
	private static final int ROW = 24;

	protected TitleScreenMixin(Component title) {
		super(title);
	}

	@ModifyExpressionValue(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/TitleScreen;createNormalMenuOptions(II)I"))
	private int myriad$menuButton(int lastRow) {
		Button button = MenuButton.create(200);
		button.setPosition(width / 2 - 100, lastRow + ROW);
		addRenderableWidget(button);
		return lastRow + ROW;
	}
}
