package dev.myriadclient.api.hook;

import dev.myriadclient.api.registry.MyriadId;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.ServerStats;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * When a contribution applies: for which module (the caller), on which anti-cheat profile, on which server. Start
 * from {@link #always()} or {@link #caller}, then narrow it:
 *
 * <pre>{@code
 * Match.caller("myriad-essentials:recast").on(AntiCheat.Profile.GRIM)
 * Match.addon("my-addon")                  // every module of one addon
 * Match.always().server("6b6t.org")
 * }</pre>
 * Where several contributions match, the most specific runs first (a caller counts most, then an addon, then a
 * server, then a profile) and can hand over to the next with {@link HookCall#proceed()}; equally specific ones run in
 * the order their addons loaded.
 */
public final class Match {
	private static final Match ALWAYS = new Match(null, null, null, null, null);

	private final @Nullable MyriadId callerId;
	private final @Nullable Class<?> callerType;
	private final @Nullable String addon;
	private final AntiCheat.@Nullable Profile profile;
	private final @Nullable String server;

	private Match(@Nullable MyriadId callerId, @Nullable Class<?> callerType, @Nullable String addon, AntiCheat.@Nullable Profile profile, @Nullable String server) {
		this.callerId = callerId;
		this.callerType = callerType;
		this.addon = addon;
		this.profile = profile;
		this.server = server;
	}

	/** Every call. */
	public static Match always() {
		return ALWAYS;
	}

	/** Calls for the module (or other feature) with this id. */
	public static Match caller(MyriadId id) {
		return new Match(Objects.requireNonNull(id), null, null, null, null);
	}

	/** Calls for the module with this id ({@code "myriad-essentials:recast"}). */
	public static Match caller(String id) {
		return caller(MyriadId.parse(id));
	}

	/** Calls for an instance of {@code type} (your own module's class, or a shared base class). */
	public static Match caller(Class<?> type) {
		return new Match(null, Objects.requireNonNull(type), null, null, null);
	}

	/**
	 * Calls for any module (or other feature) of the addon with this mod id: its modules' ids are in its namespace,
	 * and anything else is told apart by its package. For a service replacement, this keeps it to your own addon:
	 * other addons' modules go on using core's.
	 */
	public static Match addon(String modId) {
		return new Match(null, null, Objects.requireNonNull(modId), null, null);
	}

	/** Only with this anti-cheat profile in effect. */
	public Match on(AntiCheat.Profile profile) {
		return new Match(callerId, callerType, addon, Objects.requireNonNull(profile), server);
	}

	/** Only on this server or its subdomains ({@code "2b2t.org"}). */
	public Match server(String host) {
		return new Match(callerId, callerType, addon, profile, normalize(Objects.requireNonNull(host)));
	}

	/** The caller's id it requires ({@link #caller(MyriadId)}), or null. */
	public @Nullable MyriadId callerId() {
		return callerId;
	}

	/** The class a caller must be an instance of ({@link #caller(Class)}), or null. */
	public @Nullable Class<?> callerType() {
		return callerType;
	}

	/** The addon a caller must belong to ({@link #addon}), or null. */
	public @Nullable String addonId() {
		return addon;
	}

	/** The anti-cheat profile it requires ({@link #on}), or null for any. */
	public AntiCheat.@Nullable Profile profile() {
		return profile;
	}

	/** The server host it requires ({@link #server}), lower case without a port, or null for any. */
	public @Nullable String serverHost() {
		return server;
	}

	/** Whether it names who's calling: a module, a class or an addon. */
	public boolean hasCaller() {
		return callerId != null || callerType != null || addon != null;
	}

	/** Higher for narrower matches: a caller 8, an addon 4, a server 2, a profile 1. */
	public int specificity() {
		return (callerId != null || callerType != null ? 8 : 0) + (addon != null ? 4 : 0) + (server != null ? 2 : 0) + (profile != null ? 1 : 0);
	}

	/** Whether a call for {@code id}/{@code type} (of addon {@code addonId}) on {@code profile} at {@code host} is matched. */
	@ApiStatus.Internal
	public boolean test(@Nullable MyriadId id, @Nullable Class<?> type, @Nullable String addonId, AntiCheat.Profile profile, @Nullable String host) {
		if (callerId != null && !callerId.equals(id)) return false;
		if (callerType != null && (type == null || !callerType.isAssignableFrom(type))) return false;
		if (addon != null && !addon.equals(addonId)) return false;
		return testWhere(profile, host);
	}

	/** Whether the profile and server parts match, whoever calls. */
	@ApiStatus.Internal
	public boolean testWhere(AntiCheat.Profile profile, @Nullable String host) {
		if (this.profile != null && !this.profile.equals(profile)) return false;
		return server == null || ServerStats.isHost(host, server);
	}

	/** The host part of an address, lower case, without a port: what {@link #server} compares (see {@link ServerStats#hostOf}). */
	@ApiStatus.Internal
	public static @Nullable String normalize(@Nullable String address) {
		return ServerStats.hostOf(address);
	}

	/** "for Recast · on Grim · at 2b2t.org", or "always". */
	@Override
	public String toString() {
		StringBuilder s = new StringBuilder();
		if (callerId != null) s.append("for ").append(callerId);
		else if (callerType != null) s.append("for ").append(callerType.getSimpleName());
		if (addon != null) s.append(s.isEmpty() ? "" : " · ").append("for ").append(addon).append("'s modules");
		if (profile != null) s.append(s.isEmpty() ? "" : " · ").append("on ").append(profile.name());
		if (server != null) s.append(s.isEmpty() ? "" : " · ").append("at ").append(server);
		return s.isEmpty() ? "always" : s.toString();
	}

	@Override
	public boolean equals(@Nullable Object o) {
		return o instanceof Match m && Objects.equals(callerId, m.callerId) && callerType == m.callerType && Objects.equals(addon, m.addon)
			&& Objects.equals(profile, m.profile) && Objects.equals(server, m.server);
	}

	@Override
	public int hashCode() {
		return Objects.hash(callerId, callerType, addon, profile, server);
	}
}
