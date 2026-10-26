package dev.myriadclient.impl.mixin;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.CharEvent;
import dev.myriadclient.api.event.events.KeyEvent;
import dev.myriadclient.api.ui.ThemeSettings;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void myriad$onKey(long window, int action, net.minecraft.client.input.KeyEvent key, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (window != mc.getWindow().handle()) return;
		ThemeSettings.ModKey.keyEvent(key.key(), action);
		if (!Myriad.isReady()) return;
		if (Myriad.events().post(new KeyEvent(key.key(), key.scancode(), action, key.modifiers(), mc.gui.screen() != null)).isCancelled()) ci.cancel();
	}

	@Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
	private void myriad$onChar(long window, CharacterEvent event, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (window != mc.getWindow().handle() || !Myriad.isReady()) return;
		// GLFW no longer reports modifiers with typed characters.
		if (Myriad.events().post(new CharEvent(event.codepoint(), 0)).isCancelled()) ci.cancel();
	}
}
