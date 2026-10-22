package dev.myriadclient.impl.elytra;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.util.ItemInfo;
import dev.myriadclient.api.util.Packets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;

/**
 * {@link NoDurabilityEngine}'s No Durability where the glide isn't simulated: the glide broken with two starts, the
 * first one stopping it, a still tick between them (see the engine's "Elsewhere"). Subscribed while its engine is.
 */
final class GlideBreaks {
	/** Ticks of gliding between two breaks: under the 20 that wear the elytra, with room for a lost one to be made again. */
	private static final int BREAK_INTERVAL = 12;
	/** Ticks past a round trip to wait for the server to say a break stopped the glide. */
	private static final int BREAK_CONFIRM_MARGIN = 2;
	/** After a lost break, this many breaks hold still two ticks (their starts surely a server tick apart). */
	static final int LONG_BREAKS = 20;
	/** Vanilla's gliding bit in the entity flag byte ({@code Entity.FLAG_FALL_FLYING}). */
	private static final int GLIDING_BIT = 1 << 7;

	private final Minecraft mc = Minecraft.getInstance();
	/** The glide is stopped for a break, and you hold still ({@link #breakStill} more ticks). */
	private boolean breakStopped;
	/** The client glides on through the breaks (until you land, or a setback). */
	private boolean breakHold;
	private int breakStill, breakGlideTicks, breakConfirm, longBreaks;
	/** Network thread: the server said it isn't gliding you since the last break. */
	private volatile boolean stopSeen;

	/** Whether a break holds you still this tick. */
	boolean stopped() {
		return breakStopped;
	}

	/** Whether you count as gliding through the breaks. */
	boolean holding() {
		return breakHold;
	}

	void tick(LocalPlayer p, boolean wasSetBack) {
		// A setback: the server may not be gliding you any more, so the client shows what it says (the catch opens it).
		if (wasSetBack || p.onGround() || p.isInWater() || p.isInLava()) breakHold = false;
		if (breakStopped && --breakStill > 0) return;
		if (breakStopped) {
			breakStopped = false;
			breakGlideTicks = 0;
			sendStart();
			return;
		}
		var chest = p.getItemBySlot(EquipmentSlot.CHEST);
		if (!p.isFallFlying() || p.onGround() || p.isInWater() || p.isCreative() || !ItemInfo.isGlider(chest) || !chest.isDamageableItem()) {
			breakGlideTicks = 0;
			return;
		}
		breakGlideTicks++;
		// The last break unheard of: lost, so another one now, and the next ones hold still two ticks.
		boolean lost = breakConfirm > 0 && --breakConfirm == 0 && !stopSeen;
		if (lost) longBreaks = LONG_BREAKS;
		if (!lost && breakGlideTicks < BREAK_INTERVAL) return;
		// Gliding, so the server takes this start as a stop.
		stopSeen = false;
		sendStart();
		breakStopped = breakHold = true;
		breakStill = longBreaks > 0 ? 2 : 1;
		if (longBreaks > 0) longBreaks--;
		breakConfirm = Myriad.server().ping() / 50 + BREAK_CONFIRM_MARGIN;
	}

	/** Ends a break under way (the glide starts again) and the breaks' hold. */
	void end() {
		if (breakStopped && mc.player != null && mc.getConnection() != null) sendStart();
		breakStopped = breakHold = false;
		breakGlideTicks = breakConfirm = longBreaks = 0;
	}

	private void sendStart() {
		Packets.send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
	}

	/** A break's tick sends no movement (a look changed would send one): the server isn't gliding you. */
	@Subscribe(packets = ServerboundMovePlayerPacket.class)
	private void onSendMove(PacketEvent.Send e) {
		if (breakStopped) e.cancel();
	}

	/** Network thread: the server says it isn't gliding you, which a break that took shows. */
	@Subscribe(packets = ClientboundSetEntityDataPacket.class)
	private void onEntityData(PacketEvent.Receive e) {
		var p = mc.player;
		var data = (ClientboundSetEntityDataPacket) e.packet();
		if (p == null || data.id() != p.getId()) return;
		for (var value : data.packedItems()) {
			if (value.id() == 0 && value.value() instanceof Byte flags && (flags & GLIDING_BIT) == 0) stopSeen = true;
		}
	}
}
