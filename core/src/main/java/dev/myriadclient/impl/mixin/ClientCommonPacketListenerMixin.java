package dev.myriadclient.impl.mixin;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.DisconnectEvent;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.elytra.ElytraManager;
import dev.myriadclient.impl.elytra.LeavingGlide;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerMixin {
	/** DisconnectEvent, before the disconnect screen replaces the world. */
	@Inject(method = "onDisconnect", at = @At("HEAD"))
	private void myriad$disconnect(DisconnectionDetails details, CallbackInfo ci) {
		if (!Myriad.isReady()) return;
		LeavingGlide.left();
		MyriadImpl.get().serverStatsTracker().rememberServer();
		Myriad.events().post(new DisconnectEvent(details.reason(), Myriad.server().address()));
	}

	/** Glide hold: answers to the server's pings may be held back. Only on the game thread, where they're answered. */
	@Inject(method = "handlePing", at = @At("HEAD"), cancellable = true)
	private void myriad$holdPing(ClientboundPingPacket packet, CallbackInfo ci) {
		if (Minecraft.getInstance().isSameThread() && ElytraManager.holdAnswer(packet.getId())) ci.cancel();
	}
}
