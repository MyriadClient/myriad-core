package dev.myriadclient.api.service;

import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * Facts about the current server and connection that several features want, tracked once by Myriad instead of by
 * each addon: tick rate, latency, lag and rubberbands, packet rates, and totem pops.
 *
 * <pre>{@code
 * float tps = Myriad.server().tps();
 * int pops = Myriad.server().totemPops(player);
 * }</pre>
 */
@ApiStatus.NonExtendable
public interface ServerStats {
	/** Average ticks per second over the last ~20 seconds, from the spacing of time updates; 20 until known. */
	float tps();

	/**
	 * Your round trip to the server in milliseconds: how long it takes to answer an action of yours (using an item,
	 * digging), its tick included, or the tab list's latency if that's higher. 0 in singleplayer or before it's known.
	 */
	int ping();

	/** The server address you connected with ("play.example.net"), or null in singleplayer or the menus. */
	@Nullable String address();

	/** Whether you're playing a singleplayer world (the integrated server). */
	boolean isSingleplayer();

	/**
	 * A stable name for where you're playing, to key data kept per server by: the address you joined with in lower case,
	 * port and all ("2b2t.org", "play.example.net:25570"), or {@code "singleplayer:<world name>"}; null in the menus.
	 */
	@ApiStatus.AvailableSince("2.0")
	@Nullable String serverKey();

	/**
	 * Whether you're on {@code domain} or one of its subdomains, whatever the port ({@code "2b2t.org"} matches
	 * "connect.2b2t.org:25565"); false in singleplayer and the menus.
	 */
	@ApiStatus.AvailableSince("2.0")
	default boolean onHost(String domain) {
		return isHost(address(), domain);
	}

	/**
	 * The host part of a server address: lower case, without the port or a trailing dot ({@code "Play.Example.net:25570"}
	 * gives "play.example.net"; an IPv6 address keeps its colons). Null for null.
	 */
	@ApiStatus.AvailableSince("2.0")
	static @Nullable String hostOf(@Nullable String address) {
		if (address == null) return null;
		String host = address.trim().toLowerCase(Locale.ROOT);
		int colon = host.lastIndexOf(':');
		if (colon > 0 && host.indexOf(':') == colon) host = host.substring(0, colon);
		if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
		return host;
	}

	/** Whether {@code address}'s host ({@link #hostOf}) is {@code domain} or one of its subdomains. */
	@ApiStatus.AvailableSince("2.0")
	static boolean isHost(@Nullable String address, String domain) {
		String host = hostOf(address), d = hostOf(domain);
		return host != null && d != null && !d.isEmpty() && (host.equals(d) || host.endsWith("." + d));
	}

	/**
	 * Milliseconds since the server last sent anything. Normally well under a second; climbs while it lags. 0 while
	 * not connected.
	 */
	long msSinceLastPacket();

	/** Whether the server has sent nothing for {@code ms} milliseconds: pause actions it would ignore anyway. */
	default boolean isLagging(long ms) {
		return msSinceLastPacket() >= ms;
	}

	/** Milliseconds since the server last set your position (a rubberband, or a teleport); very large if never. */
	long msSinceRubberband();

	/** Whether the server snapped you back within the last {@code ms} milliseconds: back off movement tricks. */
	default boolean rubberbandedWithin(long ms) {
		return msSinceRubberband() < ms;
	}

	/** Packets you sent in the last second (strict servers kick above a few hundred). */
	int packetsSentPerSecond();

	/** Packets the server sent you in the last second. */
	int packetsReceivedPerSecond();

	/**
	 * Joins the server you were last on (or are on) again, from any screen: the disconnect screen, the title screen,
	 * or in game (which leaves first, a singleplayer world included). The connection starts on the render thread soon
	 * after; true means it's on its way. False if you haven't been on a server this session.
	 */
	boolean reconnect();

	/** The address of the last server you joined this session (the one you're on, if any), or null if none. */
	@Nullable String lastAddress();

	/** Totems {@code player} has used since they last died (reset when you join a world); 0 for players never seen pop. */
	int totemPops(UUID player);

	/** {@link #totemPops(UUID)} for {@code player}. */
	default int totemPops(Player player) {
		return totemPops(player.getUUID());
	}
}
