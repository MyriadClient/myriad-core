package dev.myriadclient.impl.mixin;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.ChatSendEvent;
import dev.myriadclient.api.event.events.HealthEvent;
import dev.myriadclient.api.event.events.TeleportEvent;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.elytra.ElytraManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPlayNetworkHandlerMixin {
	@Unique
	private boolean myriad$resending;
	/** A pilot steers: the camera before a setback, put back after it (see {@link #myriad$keepView}). */
	@Unique
	private boolean myriad$keepView;
	@Unique
	private float myriad$viewYaw, myriad$viewPitch;

	@Shadow
	public abstract void sendChat(String content);

	@Inject(method = "handleOpenScreen", at = @At("TAIL"))
	private void myriad$containerOpened(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
		if (Myriad.isReady() && MyriadImpl.get() != null) MyriadImpl.get().containerTracker().onOpened(packet.getContainerId());
	}

	@Inject(method = "handleContainerContent", at = @At("TAIL"))
	private void myriad$containerContents(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
		if (Myriad.isReady() && MyriadImpl.get() != null) MyriadImpl.get().containerTracker().onContents(packet.containerId());
	}

	@Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
	private void myriad$containerSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
		if (Myriad.isReady() && MyriadImpl.get() != null) MyriadImpl.get().containerTracker().onSlot(packet.getContainerId(), packet.getSlot());
	}

	/** TeleportEvent, on the render thread (the packet first arrives on the network thread and is handed over). */
	@Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER))
	private void myriad$teleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		Player player = Minecraft.getInstance().player;
		if (!Myriad.isReady() || player == null || player.isPassenger()) return;
		PositionMoveRotation now = PositionMoveRotation.of(player);
		Vec3 to = PositionMoveRotation.calculateAbsolute(now, packet.change(), packet.relatives()).position();
		Myriad.events().post(new TeleportEvent(now.position(), to));
	}

	/**
	 * A setback doesn't turn the camera to a pilot's rotation. Some servers (NoCheatPlus) set you back to the rotation
	 * you last sent, absolute, and while a pilot steers that's its own, not where you were looking. The server still
	 * gets the rotation it sent back: vanilla answers the setback with it before this runs.
	 */
	@Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER))
	private void myriad$beforeSetBack(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		Player p = Minecraft.getInstance().player;
		myriad$keepView = p != null && ElytraManager.steering();
		if (!myriad$keepView) return;
		myriad$viewYaw = p.getYRot();
		myriad$viewPitch = p.getXRot();
	}

	@Inject(method = "handleMovePlayer", at = @At("TAIL"))
	private void myriad$keepView(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		Player p = Minecraft.getInstance().player;
		if (!myriad$keepView || p == null) return;
		myriad$keepView = false;
		p.setYRot(myriad$viewYaw);
		p.setXRot(myriad$viewPitch);
		p.yRotO = myriad$viewYaw;
		p.xRotO = myriad$viewPitch;
	}

	/** HealthEvent, with the values before the packet is applied. */
	@Inject(method = "handleSetHealth", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER))
	private void myriad$health(ClientboundSetHealthPacket packet, CallbackInfo ci) {
		Player player = Minecraft.getInstance().player;
		if (!Myriad.isReady() || player == null || !Myriad.events().hasListeners(HealthEvent.class)) return;
		Myriad.events().post(new HealthEvent(player.getHealth(), packet.getHealth(), player.getFoodData().getFoodLevel(), packet.getFood()));
	}

	@Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
	private void myriad$onChat(String message, CallbackInfo ci) {
		if (myriad$resending || !Myriad.isReady()) return;
		ChatSendEvent event = Myriad.events().post(new ChatSendEvent(message));
		if (event.isCancelled()) {
			ci.cancel();
		} else if (!event.message().equals(message)) {
			ci.cancel();
			myriad$resending = true;
			try {
				sendChat(event.message());
			} finally {
				myriad$resending = false;
			}
		}
	}
}
