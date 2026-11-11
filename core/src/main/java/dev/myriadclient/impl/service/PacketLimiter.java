package dev.myriadclient.impl.service;

import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.PacketLimits;
import dev.myriadclient.api.util.RateCounter;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sliding-window packet budget. The limits sit under what strict servers (2b2t, Grim setups) tolerate in bursts while
 * leaving normal play and fast modules untouched: about 160 block actions a second, counted over half a second so a
 * burst can't spend a whole second's worth at once. Inventory clicks follow 2b2t, the strictest measured: it drops you
 * (the proxy's "lost connection") on the 80th click within about 4 seconds, whatever the clicks do, and 79 at once or
 * 79 every 4 seconds pass. Here it's 72 in any 5 seconds, a margin for clicks bunching up on their way: a sort, a
 * refill or a move of up to 72 clicks goes in one tick, and a long run settles at about 14 a second. Urgent clicks may
 * go past the 72 but not to the 80th. Interactions follow Paper's spam limiter
 * ({@code spam-limiter.incoming-packet-threshold}, 300 ms by default): it keeps 9 use and place packets in a window
 * starting at a packet, and silently drops the rest, so 30 a second at best. Here it's the same 9 in any 300 ms, so
 * builds get that whole 30 a second (a 330 ms margin held them to about 27), and a burst of 9 (a surround) still goes
 * at once. Lock-free: every packet sent passes through here on whichever thread sends it.
 */
public final class PacketLimiter implements PacketLimits {
	private static final int WINDOW_MS = 500;
	private static final Map<Kind, Integer> LIMITS = Map.of(Kind.BLOCK_ACTION, 80, Kind.INVENTORY, 72);
	private static final Map<Kind, Integer> WINDOWS = Map.of(Kind.BLOCK_ACTION, WINDOW_MS, Kind.INTERACT, 300, Kind.INVENTORY, 5000);
	/** Inventory clicks never go past this many in the window, urgent or not: the next one gets you dropped on 2b2t. */
	private static final int INVENTORY_CEILING = 78;

	private final Map<Kind, RateCounter> sent = new EnumMap<>(Kind.class);
	/** A smaller inventory budget and window for tests that need a job spread over ticks; 0 for the real ones. */
	private volatile int testInventoryLimit, testInventoryWindowMs;
	/** Nesting of {@link #urgent} on the render thread; other threads never see it raised. */
	private final AtomicInteger urgentDepth = new AtomicInteger();

	public PacketLimiter() {
		for (Kind k : Kind.values()) sent.put(k, new RateCounter(WINDOWS.get(k)));
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

	@Override
	public void count(Kind kind, int packets) {
		for (int i = 0; i < packets; i++) record(kind);
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
		if (urgentDepth.get() > 0) return kind != Kind.INVENTORY || used(kind) + packets <= INVENTORY_CEILING;
		return used(kind) + packets <= limit(kind);
	}

	@Override
	public int used(Kind kind) {
		return sent.get(kind).count();
	}

	/** Interactions are the anti-cheat profile's budget (9 in Myriad's, for Paper's limiter); the rest are fixed. */
	@Override
	public int limit(Kind kind) {
		if (kind == Kind.INTERACT) return AntiCheat.inEffect().interactionBudget();
		return kind == Kind.INVENTORY && testInventoryLimit > 0 ? testInventoryLimit : LIMITS.get(kind);
	}

	/** For tests: an inventory budget of {@code limit} clicks per {@code windowMs}, counted afresh. */
	public void inventoryLimitForTests(int limit, int windowMs) {
		testInventoryLimit = limit;
		testInventoryWindowMs = windowMs;
		sent.put(Kind.INVENTORY, new RateCounter(windowMs));
	}

	@Override
	public int windowMs(Kind kind) {
		return kind == Kind.INVENTORY && testInventoryWindowMs > 0 ? testInventoryWindowMs : WINDOWS.get(kind);
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
