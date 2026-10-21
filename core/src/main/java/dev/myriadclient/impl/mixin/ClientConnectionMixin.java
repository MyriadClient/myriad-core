package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.AttackEvent;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.elytra.LeavingGlide;
import dev.myriadclient.impl.network.ActionTiming;
import dev.myriadclient.impl.network.PacketGate;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(Connection.class)
public abstract class ClientConnectionMixin {
	@Unique
	private static final ThreadLocal<Boolean> MYRIAD_RESENDING = ThreadLocal.withInitial(() -> false);

	/**
	 * The hotbar slot last sent to the server, or -1 once the server has set it itself. Grim (2b2t) cancels a slot
	 * change to the slot it already has, which happens when vanilla re-sends a slot a module already sent directly
	 * (a hold or silent swap), so those duplicates are dropped here for everyone.
	 */
	@Unique
	private volatile int myriad$lastSlot = -1;

	@Shadow
	public abstract PacketFlow getReceiving();

	/** Only the client's end: in singleplayer the integrated server's connections run through here too. */
	@Unique
	private boolean myriad$isClientSide() {
		return getReceiving() == PacketFlow.CLIENTBOUND;
	}

	@Shadow
	public abstract void send(Packet<?> packet, @Nullable ChannelFutureListener callbacks, boolean flush);

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"), cancellable = true)
	private void myriad$onSend(Packet<?> packet, @Nullable ChannelFutureListener callbacks, boolean flush, CallbackInfo ci) {
		// (Held slot changes going out now were already counted when they were held.)
		if (packet instanceof ServerboundSetCarriedItemPacket slot && slot.getSlot() == myriad$lastSlot && !ActionTiming.isFlushing() && myriad$isClientSide()) {
			ci.cancel();
			return;
		}
		// Held actions going out, rewritten packets being resent, and silent sends pass as they are.
		if (ActionTiming.isFlushing() || MYRIAD_RESENDING.get() || PacketGate.isSilent() || !myriad$isClientSide()) return;
		if (!Myriad.isReady()) {
			if (ActionTiming.get().holdIfLate((Connection) (Object) this, packet, callbacks, flush)) myriad$held(packet, ci);
			return;
		}
		if (packet instanceof ServerboundAttackPacket attack && Myriad.events().hasListeners(AttackEvent.class)) {
			Minecraft mc = Minecraft.getInstance();
			Entity target = mc.level == null ? null : mc.level.getEntity(attack.entityId());
			if (target != null && Myriad.events().post(new AttackEvent(target)).isCancelled()) {
				ci.cancel();
				return;
			}
		}
		PacketEvent.Send event = Myriad.events().post(new PacketEvent.Send(packet));
		if (event.isCancelled()) {
			ci.cancel();
		} else if (ActionTiming.get().holdIfLate((Connection) (Object) this, event.packet(), callbacks, flush)) {
			// Too late in the tick for an action: it goes out first thing next tick (see ActionTiming).
			myriad$held(event.packet(), ci);
		} else if (event.packet() != packet) {
			ci.cancel();
			MYRIAD_RESENDING.set(true);
			try {
				send(event.packet(), callbacks, flush);
			} finally {
				MYRIAD_RESENDING.set(false);
			}
		}
	}

	/**
	 * A packet held for the next tick still goes out, in order: a slot change held counts as the last slot sent, or the
	 * next one (switching back) would be taken for a duplicate and dropped, leaving the server on the wrong slot.
	 */
	@Unique
	private void myriad$held(Packet<?> packet, CallbackInfo ci) {
		if (packet instanceof ServerboundSetCarriedItemPacket slot) myriad$lastSlot = slot.getSlot();
		// Counted against the budget now, not when it goes out: more sent later this tick must see it, or a burst made
		// after the movement packet all goes out at once next tick, past what the server keeps.
		if (MyriadImpl.get() != null) MyriadImpl.get().packetLimiter().record(packet);
		ci.cancel();
	}

	/**
	 * The client closing the connection itself, on the render thread: gliding with No Durability's chestplate on, the
	 * elytra goes back on and the glide is started first, so you rejoin gliding (see {@link LeavingGlide}).
	 */
	@Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"))
	private void myriad$beforeDisconnect(DisconnectionDetails details, CallbackInfo ci) {
		if (myriad$isClientSide() && ((Connection) (Object) this).isConnected() && Myriad.isReady() && Minecraft.getInstance().isSameThread()) {
			LeavingGlide.beforeLeaving();
		}
	}

	/** Counts what actually went out (after any rewrite or cancel) against the packet budget. */
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("TAIL"))
	private void myriad$countSent(Packet<?> packet, @Nullable ChannelFutureListener callbacks, boolean flush, CallbackInfo ci) {
		if (packet instanceof ServerboundSetCarriedItemPacket slot) myriad$lastSlot = slot.getSlot();
		if (myriad$isClientSide()) ActionTiming.get().sent(packet);
		// (Held actions going out now were counted when they were held.)
		if (myriad$isClientSide() && !ActionTiming.isFlushing() && MyriadImpl.get() != null) MyriadImpl.get().packetLimiter().record(packet);
	}

	/** Wraps packet handling so receive handlers can drop or replace packets. */
	@WrapMethod(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V")
	private void myriad$onReceive(ChannelHandlerContext ctx, Packet<?> packet, Operation<Void> original) {
		if (packet instanceof ClientboundSetHeldSlotPacket) myriad$lastSlot = -1;
		if (!Myriad.isReady() || !myriad$isClientSide()) {
			original.call(ctx, packet);
			return;
		}
		if (packet instanceof BundlePacket<?> bundle) {
			myriad$filterBundle(bundle);
			original.call(ctx, packet);
			return;
		}
		PacketEvent.Receive event = Myriad.events().post(new PacketEvent.Receive(packet));
		if (!event.isCancelled()) original.call(ctx, event.packet());
	}

	/** Runs each packet of a bundle through the event separately, dropping cancelled ones. */
	@Unique
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void myriad$filterBundle(BundlePacket<?> bundle) {
		List<Packet<?>> kept = new ArrayList<>();
		for (Packet<?> inner : bundle.subPackets()) {
			PacketEvent.Receive e = Myriad.events().post(new PacketEvent.Receive(inner));
			if (!e.isCancelled()) kept.add(e.packet());
		}
		((BundlePacket) bundle).packets = kept;
	}
}
