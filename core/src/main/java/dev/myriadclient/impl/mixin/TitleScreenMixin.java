package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.myriadclient.impl.ui.MenuButton;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Minecraft's title screen (Myriad's own off: Theme → Preferences → Title Screen) with Myriad's button at the end of its
 * row of icon buttons (friends, language, accessibility), the row centred again with it.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
	protected TitleScreenMixin(Component title) {
		super(title);
	}

	@ModifyArg(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/TitleScreen;getHorizontalPosition(III)I"), index = 1)
	private int myriad$oneMore(int buttons) {
		return buttons + 1;
	}

	/** After the last of the row (accessibility), four pixels on, as the row spaces them. */
	@WrapOperation(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/SpriteIconButton;setPosition(II)V", ordinal = 1))
	private void myriad$menuButton(SpriteIconButton last, int x, int y, Operation<Void> original) {
		original.call(last, x, y);
		SpriteIconButton button = MenuButton.create();
		button.setPosition(x + last.getWidth() + 4, y);
		addRenderableWidget(button);
	}
}
