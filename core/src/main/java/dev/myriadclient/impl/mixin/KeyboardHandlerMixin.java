package dev.myriadclient.impl.mixin;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.CharEvent;
import dev.myriadclient.api.event.events.KeyEvent;
import dev.myriadclient.api.input.Keybind;
import dev.myriadclient.api.ui.ThemeSettings;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Input: key presses and typed characters from the game window, for KeyEvent and CharEvent (binds, the UI, cancelling
 * vanilla's handling) and the theme's modifier key. Hooked before vanilla handles them so a handler can cancel them.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void myriad$onKey(long window, int action, net.minecraft.client.input.KeyEvent key, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (window != mc.getWindow().handle()) return;
		ThemeSettings.ModKey.keyEvent(key.key(), action);
		if (!Myriad.isReady()) return;
		if (Myriad.events().post(new KeyEvent(key.key(), key.keycode(), action, key.modifiers(), mc.gui.screen() != null)).isCancelled()) ci.cancel();
	}

	@Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
	private void myriad$onChar(long window, CharacterEvent event, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (window != mc.getWindow().handle() || !Myriad.isReady()) return;
		// The window doesn't report modifiers with typed characters: the keys held now are.
		if (Myriad.events().post(new CharEvent(event.codepoint(), Keybind.heldModifiers())).isCancelled()) ci.cancel();
	}
}
