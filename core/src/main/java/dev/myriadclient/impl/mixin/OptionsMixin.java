package dev.myriadclient.impl.mixin;

import dev.myriadclient.impl.cosmetic.MyriadCape;
import net.minecraft.client.Options;
import net.minecraft.server.level.ClientInformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The skin parts sent to the server carry the Myriad cape bit while the cape is on ({@link MyriadCape}). */
@Mixin(Options.class)
public abstract class OptionsMixin {
	@Inject(method = "buildPlayerInformation", at = @At("RETURN"), cancellable = true)
	private void myriad$capeBit(CallbackInfoReturnable<ClientInformation> cir) {
		ClientInformation i = cir.getReturnValue();
		int parts = MyriadCape.report(i.modelCustomisation());
		if (parts == i.modelCustomisation()) return;
		cir.setReturnValue(new ClientInformation(i.language(), i.viewDistance(), i.chatVisibility(), i.chatColors(), parts,
			i.mainHand(), i.textFilteringEnabled(), i.allowsListing(), i.particleStatus()));
	}
}
