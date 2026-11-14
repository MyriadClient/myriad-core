package dev.myriadclient.impl.service;

import dev.myriadclient.api.service.ServerStats;
import dev.myriadclient.impl.network.KnownServers;
import net.minecraft.world.entity.player.Input;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * What Grim (MultiActionsC) refuses an inventory click for, which depends on the version the server sees you as: it
 * judges sprinting for every version, the movement keys and jump of the last input packet from 1.21.2 (older clients
 * don't send them), and sneaking before 1.15 and from 1.21.9. Some servers run a Grim from before it judged the keys
 * ({@link #NO_INPUT_RULE}).
 * <p>
 * Sprinting is the cheapest to get past: Grim takes it from the sprint packets as they arrive, so a click goes between a
 * stop and a start sent in the same tick (see InventoryManager.prepareClick).
 */
public enum ClickRules {
	/** 1.15 to 1.21.1: only sprinting. */
	SPRINT(false, false),
	/** 1.21.2 to 1.21.8: sprinting, or moving or jumping in the last input. */
	INPUT(true, false),
	/** 1.21.9 on (and before 1.15), or a version that couldn't be told: all of it, sneaking too. */
	ALL(true, true),
	/** 1.21.9 on, on a server whose Grim doesn't judge the keys ({@link #NO_INPUT_RULE}): sprinting and sneaking. */
	SNEAK(false, true);

	/**
	 * Hosts (with their subdomains) whose Grim takes a click whatever keys the last input held: on 2b2t, clicks made with
	 * forward or jump held in the last input packet are all applied, where current Grim cancels them.
	 */
	static final Set<String> NO_INPUT_RULE = Set.of(KnownServers.TWO_B2T);
	/**
	 * More such hosts, comma-separated, from {@code -Dmyriad.noInputRuleHosts}: dev runs pass "localhost" so the Grim
	 * test server, set up like 2b2t's, is clicked on as 2b2t is.
	 */
	private static final Set<String> EXTRA_HOSTS = Set.copyOf(Arrays.asList(System.getProperty("myriad.noInputRuleHosts", "").toLowerCase(Locale.ROOT).split("\\s*,\\s*")));

	/** Protocol numbers of the versions the rules change at. */
	static final int V1_15 = 573, V1_21_2 = 768, V1_21_9 = 773;

	private final boolean input, sneak;

	ClickRules(boolean input, boolean sneak) {
		this.input = input;
		this.sneak = sneak;
	}

	/** The rules for a client the server sees as protocol {@code protocol}; {@link #ALL} when it's unknown (-1). */
	public static ClickRules forProtocol(int protocol) {
		if (protocol < V1_15 || protocol >= V1_21_9) return ALL;
		return protocol >= V1_21_2 ? INPUT : SPRINT;
	}

	/** {@link #forProtocol}, without the keys on a server known not to judge them ({@code address} as joined, port and all). */
	public static ClickRules forServer(int protocol, @Nullable String address) {
		ClickRules rules = forProtocol(protocol);
		if (!rules.input || !ignoresInput(address)) return rules;
		return rules.sneak ? SNEAK : SPRINT;
	}

	static boolean ignoresInput(@Nullable String address) {
		String host = ServerStats.hostOf(address);
		if (host == null || host.isEmpty()) return false;
		for (String h : NO_INPUT_RULE) if (ServerStats.isHost(host, h)) return true;
		return EXTRA_HOSTS.contains(host);
	}

	/** Whether the movement keys and jump of the last input packet count. */
	public boolean input() {
		return input;
	}

	/** Whether sneaking counts. */
	public boolean sneak() {
		return sneak;
	}

	/** Whether a click now would be refused, given the last input the server got and its sprint state. */
	public boolean refuses(Input sent, boolean sprinting) {
		return sprinting || refusesKeys(sent);
	}

	/** Whether the last input the server got refuses a click, sprinting aside: what only a still tick gets past. */
	public boolean refusesKeys(Input sent) {
		if (input && (sent.forward() || sent.backward() || sent.left() || sent.right() || sent.jump())) return true;
		return sneak && sent.shift();
	}
}
