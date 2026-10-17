package dev.myriadclient.impl.mixin;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.PlaySoundEvent;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
	@Inject(method = "play", at = @At("HEAD"), cancellable = true)
	private void myriad$play(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
		if (myriad$cancelled(sound)) cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
	}

	@Inject(method = "playDelayed", at = @At("HEAD"), cancellable = true)
	private void myriad$playDelayed(SoundInstance sound, int delay, CallbackInfo ci) {
		if (myriad$cancelled(sound)) ci.cancel();
	}

	private static boolean myriad$cancelled(SoundInstance sound) {
		return Myriad.isReady() && Myriad.events().hasListeners(PlaySoundEvent.class) && Myriad.events().post(new PlaySoundEvent(sound)).isCancelled();
	}
}
