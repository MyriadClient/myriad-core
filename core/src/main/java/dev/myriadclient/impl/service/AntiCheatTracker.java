package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.service.AntiCheat;
import net.minecraft.network.protocol.common.ClientboundPingPacket;

import java.util.Locale;
import java.util.Set;

/**
 * The anti-cheat profile: the player's choice, or (Auto) a guess from the server. Anti-cheats that simulate the player
 * send a ping ("transaction") with nearly everything that changes the player, many a second; the vanilla server never
 * sends one in game. A few of them in a row mean such an anti-cheat.
 */
public final class AntiCheatTracker implements AntiCheat {
	/** Pings seen since joining before the server counts as running one. */
	private static final int PINGS = 5;
	/** Servers known to run Grim, by host (and their subdomains); the defaults plus the player's. */
	public static final Set<String> DEFAULT_KNOWN = Set.of("2b2t.org");
	private volatile Set<String> known = DEFAULT_KNOWN;

	private volatile Mode mode = Mode.AUTO;
	private volatile int pings;
	private volatile boolean onKnown;

	@Override
	public Profile profile() {
		return switch (mode) {
			case AUTO -> detected();
			case VANILLA -> Profile.VANILLA;
			case GRIM -> Profile.GRIM;
		};
	}

	@Override
	public Profile detected() {
		return onKnown || pings >= PINGS ? Profile.GRIM : Profile.VANILLA;
	}

	@Override
	public Mode mode() {
		return mode;
	}

	@Override
	public void setMode(Mode mode) {
		if (mode == null || mode == this.mode) return;
		this.mode = mode;
		if (Myriad.isReady()) Myriad.config().markDirty();
	}

	@Subscribe(packets = ClientboundPingPacket.class)
	private void onReceive(PacketEvent.Receive e) {
		if (pings < PINGS) pings++;
	}

	@Subscribe
	private void onJoin(WorldEvent.Join e) {
		pings = 0;
		String address = Myriad.server().address();
		onKnown = address != null && isKnown(address.toLowerCase(Locale.ROOT));
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		pings = 0;
		onKnown = false;
	}

	@Override
	public Set<String> knownServers() {
		return known;
	}

	@Override
	public boolean addKnownServer(String host) {
		String h = normalize(host);
		if (h.isEmpty() || known.contains(h)) return false;
		Set<String> next = new java.util.TreeSet<>(known);
		next.add(h);
		known = java.util.Collections.unmodifiableSet(next);
		if (Myriad.isReady()) Myriad.config().markDirty();
		return true;
	}

	@Override
	public boolean removeKnownServer(String host) {
		String h = normalize(host);
		if (!known.contains(h)) return false;
		Set<String> next = new java.util.TreeSet<>(known);
		next.remove(h);
		known = java.util.Collections.unmodifiableSet(next);
		if (Myriad.isReady()) Myriad.config().markDirty();
		return true;
	}

	/** Replaces the list (config load). */
	public void setKnownServers(java.util.Collection<String> hosts) {
		Set<String> next = new java.util.TreeSet<>();
		for (String h : hosts) {
			String n = normalize(h);
			if (!n.isEmpty()) next.add(n);
		}
		known = java.util.Collections.unmodifiableSet(next);
	}

	private static String normalize(String host) {
		String h = host.trim().toLowerCase(Locale.ROOT);
		int colon = h.lastIndexOf(':');
		return colon > 0 ? h.substring(0, colon) : h;
	}

	private boolean isKnown(String address) {
		int colon = address.lastIndexOf(':');
		String host = colon > 0 ? address.substring(0, colon) : address;
		for (String k : known) if (host.equals(k) || host.endsWith("." + k)) return true;
		return false;
	}
}
