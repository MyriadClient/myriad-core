package dev.myriadclient.impl.mixin;

import dev.myriadclient.impl.ui.MenuButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FriendsButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft's title screen (Myriad's own off: Theme → Preferences → Themed Screens) with a Myriad icon button beside its
 * friends, language and accessibility ones, which move over to make room. A full-width button of its own took a row,
 * and in a small window that pushed Options and Quit off the bottom.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
	@Shadow
	private @Nullable FriendsButton friends;

	protected TitleScreenMixin(Component title) {
		super(title);
	}

	@Shadow
	private int getHorizontalPosition(int currentButton, int numberOfButtons, int buttonWidth) {
		throw new AssertionError();
	}

	/** The icon row has room for one more. */
	@ModifyArg(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/TitleScreen;getHorizontalPosition(III)I"), index = 1)
	private int myriad$iconCount(int count) {
		return count + 1;
	}

	/** Myriad's goes last in it, once Minecraft's three are placed (just before Options is made). */
	@Inject(method = "init", at = @At(value = "CONSTANT", args = "stringValue=menu.options"))
	private void myriad$menuIcon(CallbackInfo ci) {
		if (friends == null) return;
		Button button = MenuButton.icon();
		button.setPosition(getHorizontalPosition(4, 4, button.getWidth()), friends.getY());
		addRenderableWidget(button);
	}
}
