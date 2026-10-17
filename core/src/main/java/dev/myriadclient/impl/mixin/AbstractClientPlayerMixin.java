package dev.myriadclient.impl.mixin;

import dev.myriadclient.impl.cosmetic.MyriadCape;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Players wearing Myriad's cape get it (and its elytra) in place of any other ({@link MyriadCape}). */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void myriad$cape(CallbackInfoReturnable<PlayerSkin> cir) {
		if (MyriadCape.wears((AbstractClientPlayer) (Object) this)) cir.setReturnValue(MyriadCape.dress(cir.getReturnValue()));
	}
}
