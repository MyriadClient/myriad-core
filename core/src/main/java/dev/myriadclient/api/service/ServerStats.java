package dev.myriadclient.api.service;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import net.minecraft.world.entity.player.Player;

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

	boolean isSingleplayer();

	/** Milliseconds since the server last sent anything. Normally well under a second; climbs while it lags. */
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

	int packetsReceivedPerSecond();

	/**
	 * Joins the server you were last on (or are on) again, from any screen: the disconnect screen, the title screen,
	 * or in game (which leaves first). False if there was none, or you're in singleplayer.
	 */
	boolean reconnect();

	/** The address of the last server you joined this session, or null. */
	@Nullable String lastAddress();

	/** Totems {@code player} has used since they last died (reset when you join a world). */
	int totemPops(UUID player);

	default int totemPops(Player player) {
		return totemPops(player.getUUID());
	}
}
