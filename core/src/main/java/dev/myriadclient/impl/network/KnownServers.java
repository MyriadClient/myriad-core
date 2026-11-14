package dev.myriadclient.impl.network;

/**
 * Servers core treats specially by name, with what's known about each: the hosts (and their subdomains) that pick an
 * anti-cheat profile or click rules without waiting to detect them. Compared with {@code ServerStats.isHost}.
 */
public final class KnownServers {
	/**
	 * 2b2t: Grim, built so that placements and item uses go without a swing, a slot swapped in goes back in the same tick,
	 * a decoy start fools its break timing, and a click is taken whatever keys the last input held (live captures).
	 */
	public static final String TWO_B2T = "2b2t.org";
	/** 6b6t: a NoCheatPlus fork, which checks the glide against loose limits rather than simulating it. */
	public static final String SIX_B6T = "6b6t.org";

	private KnownServers() {
	}
}
