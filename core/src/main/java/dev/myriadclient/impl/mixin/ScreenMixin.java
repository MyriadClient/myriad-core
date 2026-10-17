package dev.myriadclient.impl.mixin;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.impl.MyriadImpl;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class ScreenMixin {
	/** Vanilla only runs "/" commands from clicked chat text; run Myriad's (from Texts.command) too. */
	@Inject(method = "clickCommandAction", at = @At("HEAD"), cancellable = true)
	private static void myriad$runCommand(LocalPlayer player, String command, @Nullable Screen screenAfterCommand, CallbackInfo ci) {
		if (!Myriad.isReady() || MyriadImpl.get() == null) return;
		String prefix = Myriad.config().commandPrefix();
		if (prefix.isEmpty() || !command.startsWith(prefix)) return;
		MyriadImpl.get().commandManager().execute(command.substring(prefix.length()));
		ci.cancel();
	}
}
