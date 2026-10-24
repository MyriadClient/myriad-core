package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.registry.Registry;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.network.JoinedVersion;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The anti-cheat profile: the player's choice, or (Auto) a guess from the server. Anti-cheats that simulate the player
 * send a ping ("transaction") with nearly everything that changes the player, many a second; the vanilla server never
 * sends one in game. A few of them in a row mean such an anti-cheat. NoCheatPlus sends none, so it's known by host.
 * <p>
 * The guess is worked out when something it depends on changes (joining, the pings, the known hosts, a profile coming
 * or going) and every tick while an addon answers {@link AntiCheat#DETECT}, and kept: {@link #profile()} is read by
 * every {@code forServer()} preset, so it's a field read.
 */
public final class AntiCheatTracker implements AntiCheat {
	/** Pings seen since joining before the server counts as running one. */
	private static final int PINGS = 5;
	/** Pings counted at most, for {@link ServerSeen#pings()}. */
	private static final int MAX_PINGS = 100;
	/** Servers known to run Grim, by host (and their subdomains); the defaults plus the player's. */
	public static final Set<String> DEFAULT_KNOWN = Set.of("2b2t.org");
	/** 2b2t, whose Grim lets placements go without a swing and swaps back in the same tick ({@link Profile#GRIM_2B2T}). */
	static final Set<String> TWO_B2T_HOSTS = Set.of("2b2t.org");
	/** Servers known to run NoCheatPlus: 6b6t's fork has been live there since 2026-10-10. */
	static final Set<String> NCP_HOSTS = Set.of("6b6t.org");
	private volatile Set<String> known = DEFAULT_KNOWN;

	private final Registry<Profile> profiles = new Registry<>("anti-cheat profile");
	/** The chosen profile's id, kept while the addon that registers it hasn't yet (config loads first), or null for Auto. */
	private volatile @Nullable MyriadId chosenId;
	private volatile @Nullable Profile chosen;
	private volatile Profile detected = Profile.VANILLA;
	private volatile int pings;
	/** {@link #pings} when the guess was last made. */
	private int pingsAtDetect;
	private volatile @Nullable String host;
	private volatile boolean singleplayer;

	public AntiCheatTracker() {
		for (Profile p : new Profile[]{Profile.VANILLA, Profile.GRIM, Profile.GRIM_2B2T, Profile.NCP}) profiles.register(p, "myriad");
		profiles.onAdd(p -> resolve());
		profiles.onRemove(p -> resolve());
	}

	@Override
	public Profile profile() {
		Profile c = chosen;
		return c != null ? c : detected;
	}

	@Override
	public Profile detected() {
		return detected;
	}

	@Override
	public @Nullable Profile choice() {
		return chosen;
	}

	@Override
	public void choose(@Nullable Profile profile) {
		MyriadId id = profile == null ? null : profile.id();
		if (id != null && !profiles.contains(id)) throw new IllegalArgumentException("Anti-cheat profile " + id + " isn't registered");
		if (Objects.equals(id, chosenId)) return;
		chosenId = id;
		resolve();
		if (Myriad.isReady()) Myriad.config().markDirty();
	}

	/** The chosen profile's id as saved, or null for Auto. */
	public @Nullable MyriadId choiceId() {
		return chosenId;
	}

	/** Restores the choice from the config: the profile may belong to an addon that registers it later. */
	public void setChoiceId(@Nullable MyriadId id) {
		chosenId = id;
		resolve();
	}

	@Override
	public Registry<Profile> profiles() {
		return profiles;
	}

	private void resolve() {
		MyriadId id = chosenId;
		chosen = id == null ? null : profiles.get(id).orElse(null);
		detect();
	}

	/** Network thread: only counts. The guess changes on the next tick, so addons' detection never runs off-thread. */
	@Subscribe(packets = ClientboundPingPacket.class)
	private void onReceive(PacketEvent.Receive e) {
		if (pings < MAX_PINGS) pings++;
	}

	@Subscribe(priority = Priority.HIGHEST + 100)
	private void onTick(TickEvent.Pre e) {
		if (DETECT.hooked() || pingsAtDetect < PINGS && pings >= PINGS) detect();
	}

	@Subscribe
	private void onJoin(WorldEvent.Join e) {
		pings = 0;
		String address = Myriad.server().address();
		host = address == null ? null : normalize(address);
		singleplayer = Myriad.server().isSingleplayer();
		detect();
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		pings = 0;
		host = null;
		singleplayer = false;
		detect();
	}

	private void detect() {
		pingsAtDetect = pings;
		boolean inGame = host != null || singleplayer;
		ServerSeen seen = new ServerSeen(host, singleplayer, pingsAtDetect, inGame ? joinedProtocol() : -1);
		Profile p = DETECT.hooked() ? DETECT.call(null, seen, this::guess) : null;
		detected = p != null ? p : guess(seen);
	}

	/** Core's own guess: a known Grim host (2b2t's own profile there) or the pings, then a known NoCheatPlus host, else the vanilla server. */
	Profile guess(ServerSeen seen) {
		if (seen.host() != null && matches(seen.host(), known)) return matches(seen.host(), TWO_B2T_HOSTS) ? Profile.GRIM_2B2T : Profile.GRIM;
		if (seen.pings() >= PINGS) return Profile.GRIM;
		if (seen.host() != null && matches(seen.host(), NCP_HOSTS)) return Profile.NCP;
		return Profile.VANILLA;
	}

	@Override
	public Set<String> knownServers() {
		return known;
	}

	@Override
	public boolean addKnownServer(String host) {
		String h = normalize(host);
		if (h.isEmpty() || known.contains(h)) return false;
		Set<String> next = new TreeSet<>(known);
		next.add(h);
		known = Collections.unmodifiableSet(next);
		detect();
		if (Myriad.isReady()) Myriad.config().markDirty();
		return true;
	}

	@Override
	public boolean removeKnownServer(String host) {
		String h = normalize(host);
		if (!known.contains(h)) return false;
		Set<String> next = new TreeSet<>(known);
		next.remove(h);
		known = Collections.unmodifiableSet(next);
		detect();
		if (Myriad.isReady()) Myriad.config().markDirty();
		return true;
	}

	/** Replaces the list (config load). */
	public void setKnownServers(Collection<String> hosts) {
		Set<String> next = new TreeSet<>();
		for (String h : hosts) {
			String n = normalize(h);
			if (!n.isEmpty()) next.add(n);
		}
		known = Collections.unmodifiableSet(next);
		detect();
	}

	private static String normalize(String host) {
		String h = host.trim().toLowerCase(Locale.ROOT);
		int colon = h.lastIndexOf(':');
		return colon > 0 ? h.substring(0, colon) : h;
	}

	private static boolean matches(String host, Set<String> hosts) {
		for (String k : hosts) if (host.equals(k) || host.endsWith("." + k)) return true;
		return false;
	}

	@Override
	public int joinedProtocol() {
		return JoinedVersion.get().protocol();
	}

	@Override
	public String joinedVersion() {
		return JoinedVersion.get().name();
	}

	@Override
	public boolean isTranslated() {
		return JoinedVersion.get().translated();
	}
}
