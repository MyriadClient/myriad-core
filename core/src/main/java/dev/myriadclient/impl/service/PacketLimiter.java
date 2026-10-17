package dev.myriadclient.impl.service;

import dev.myriadclient.api.service.PacketLimits;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

import dev.myriadclient.api.util.RateCounter;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

/**
 * Sliding-window packet budget. The limits sit under what strict servers (2b2t, Grim setups) tolerate in bursts while
 * leaving normal play and fast modules untouched: about 160 block actions, 30 interactions and 16 inventory clicks a
 * second, counted over half a second so a burst can't spend a whole second's worth at once. Lock-free: every packet
 * sent passes through here on whichever thread sends it.
 */
public final class PacketLimiter implements PacketLimits {
	private static final int WINDOW_MS = 500;
	private static final Map<Kind, Integer> LIMITS = Map.of(Kind.BLOCK_ACTION, 80, Kind.INTERACT, 15, Kind.INVENTORY, 8);

	private final Map<Kind, RateCounter> sent = new EnumMap<>(Kind.class);
	/** Nesting of {@link #urgent} on the render thread; other threads never see it raised. */
	private final AtomicInteger urgentDepth = new AtomicInteger();

	public PacketLimiter() {
		for (Kind k : Kind.values()) sent.put(k, new RateCounter(WINDOW_MS));
	}

	/** Counts a packet the client is sending; called for every outgoing packet, including silent ones. */
	public void record(Packet<?> packet) {
		Kind kind = kindOf(packet);
		if (kind != null) record(kind);
	}

	void record(Kind kind) {
		sent.get(kind).record();
	}

	/**
	 * Counts an inventory click as it's made. Clicks are counted there, not as packets: ViaFabricPlus sends them to
	 * servers of 1.21.4 and older itself, past the connection where other packets are counted.
	 */
	public void recordClick() {
		record(Kind.INVENTORY);
	}

	static Kind kindOf(Packet<?> packet) {
		return switch (packet) {
			case ServerboundPlayerActionPacket p -> Kind.BLOCK_ACTION;
			case ServerboundUseItemOnPacket p -> Kind.INTERACT;
			case ServerboundUseItemPacket p -> Kind.INTERACT;
			case ServerboundInteractPacket p -> Kind.INTERACT;
			case ServerboundAttackPacket p -> Kind.INTERACT;
			default -> null;
		};
	}

	@Override
	public boolean canSend(Kind kind, int packets) {
		return urgentDepth.get() > 0 || used(kind) + packets <= limit(kind);
	}

	@Override
	public int used(Kind kind) {
		return sent.get(kind).count();
	}

	@Override
	public int limit(Kind kind) {
		return LIMITS.get(kind);
	}

	@Override
	public int windowMs() {
		return WINDOW_MS;
	}

	@Override
	public void urgent(Runnable action) {
		urgentDepth.incrementAndGet();
		try {
			action.run();
		} finally {
			urgentDepth.decrementAndGet();
		}
	}
}
