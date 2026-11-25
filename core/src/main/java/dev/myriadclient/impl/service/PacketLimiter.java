package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.PacketLimits;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sliding-window packet budget, counted from each packet's exact send time (see {@link SendWindow}). The limits sit
 * under what strict servers (2b2t, Grim setups) tolerate in bursts while leaving normal play and fast modules
 * untouched: about 160 block actions a second, counted over half a second so a burst can't spend a whole second's
 * worth at once. Inventory clicks follow 2b2t, the strictest measured: it drops you (the proxy's "lost connection") on
 * the 80th click within about 4 seconds, sliding, whatever the clicks do; 79 at once pass. Here it's 79 in any 4.5
 * seconds (4.2 got you dropped: two bursts that far apart by your clock can reach the server closer together), all but
 * the last {@value #URGENT_RESERVE} for ordinary clicks, which urgent ones (a totem) may also use: a sort, a refill or a
 * move of up to 76 clicks goes in one tick, and a long run settles at about 17 a second. Every
 * click packet counts, made through the game or sent raw. Recipe book placements don't count with them (2b2t took 75
 * clicks and 10 placements at once); Paper limits those apart, and whoever places them keeps to that. Interactions
 * follow Paper's spam limiter ({@code spam-limiter.incoming-packet-threshold}, 300 ms by default): it keeps 9 use and
 * place packets in a window starting at a packet, and silently drops the rest. Here it's the same 9 in any 300 ms
 * plus {@value #INTERACT_MARGIN_MS} ms for the packets bunching up on their way (any 9 the server sees inside 300 ms
 * then left at least that far apart; with 40 ms, 2b2t refused about one placement in 70 at the window's edge), about
 * 24 a second, and a burst of 9 (a surround) still goes at once. With
 * Vanilla picked by hand (not just detected), none of this applies: a vanilla server limits none of them.
 * Thread-safe: every packet sent passes through here on whichever thread sends it.
 */
public final class PacketLimiter implements PacketLimits {
	private static final int WINDOW_MS = 500;
	/** Paper's spam limiter window, and the margin added to it for packets that bunch up on their way. */
	private static final int PAPER_WINDOW_MS = 300, INTERACT_MARGIN_MS = 80;
	/** Inventory clicks never go past this many in the window, urgent or not: the next one gets you dropped on 2b2t. */
	private static final int INVENTORY_CEILING = 79;
	/** The last few clicks of the window, kept for urgent ones (a totem after a pop mid-sort). */
	private static final int URGENT_RESERVE = 3;
	private static final Map<Kind, Integer> LIMITS = Map.of(Kind.BLOCK_ACTION, 80, Kind.INVENTORY, INVENTORY_CEILING - URGENT_RESERVE);
	private static final Map<Kind, Integer> WINDOWS = Map.of(Kind.BLOCK_ACTION, WINDOW_MS,
		Kind.INTERACT, PAPER_WINDOW_MS + INTERACT_MARGIN_MS, Kind.INVENTORY, 4500);

	private final Map<Kind, SendWindow> sent = new EnumMap<>(Kind.class);
	/**
	 * Clicks counted as they were made whose packet hasn't gone through the connection yet: it isn't counted again
	 * there. Only the render thread clicks; reset after each click, so one Via sends itself (never seen) isn't kept.
	 */
	private int clicksAhead;
	/** A smaller inventory budget and window for tests that need a job spread over ticks; 0 for the real ones. */
	private volatile int testInventoryLimit, testInventoryWindowMs;
	/** Nesting of {@link #urgent} on the render thread; other threads never see it raised. */
	private final AtomicInteger urgentDepth = new AtomicInteger();

	public PacketLimiter() {
		for (Kind k : Kind.values()) sent.put(k, new SendWindow(WINDOWS.get(k)));
	}

	/**
	 * Counts a packet the client is sending; called for every outgoing packet, including silent ones. A click packet
	 * counts unless it was counted as it was made ({@link #recordClick}): one sent raw counts here.
	 */
	public void record(Packet<?> packet) {
		if (packet instanceof ServerboundContainerClickPacket) {
			synchronized (this) {
				if (clicksAhead > 0) {
					clicksAhead--;
					return;
				}
			}
			record(Kind.INVENTORY);
			return;
		}
		Kind kind = kindOf(packet);
		if (kind != null) record(kind);
	}

	/** Takes back the last {@code kind} counted: a click whose packet went out as a pick instead (see PickSwap). */
	void uncount(Kind kind) {
		sent.get(kind).unrecord();
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
		synchronized (this) {
			// One click at a time is made and sent; one left over (Via sent it, or the click was called off) is stale.
			clicksAhead = 1;
		}
	}

	/** The click {@link #recordClick} counted is done: its packet went out by now, through the connection or not. */
	public synchronized void clickDone() {
		clicksAhead = 0;
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
			// Attacks and using items on entities aren't counted: Paper's limiter only takes uses and placements.
			default -> null;
		};
	}

	/** Whether Vanilla was picked by hand: then there's no budget (see the class doc). Auto's Vanilla keeps it. */
	private static boolean lifted() {
		if (!Myriad.isReady()) return false;
		AntiCheat.Profile chosen = Myriad.antiCheat().choice();
		return chosen != null && chosen.id().equals(AntiCheat.Profile.VANILLA.id());
	}

	@Override
	public boolean canSend(Kind kind, int packets) {
		if (lifted()) return true;
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
		if (lifted()) return Integer.MAX_VALUE;
		if (kind == Kind.INTERACT) return AntiCheat.inEffect().interactionBudget();
		return kind == Kind.INVENTORY && testInventoryLimit > 0 ? testInventoryLimit : LIMITS.get(kind);
	}

	/** For tests: an inventory budget of {@code limit} clicks per {@code windowMs}, counted afresh. */
	public void inventoryLimitForTests(int limit, int windowMs) {
		testInventoryLimit = limit;
		testInventoryWindowMs = windowMs;
		sent.put(Kind.INVENTORY, new SendWindow(windowMs));
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
