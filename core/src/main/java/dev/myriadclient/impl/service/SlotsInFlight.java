package dev.myriadclient.impl.service;

import dev.myriadclient.api.util.Slots;
import dev.myriadclient.api.util.Ticks;
import dev.myriadclient.impl.MyriadImpl;

/**
 * Inventory slots the server may still be about to answer for. With lag, an inventory update the server sent about a
 * slot before it had the client's latest click there arrives after the client changed that slot, and shows the old
 * contents again until the next update: a placement made from that view places the wrong block, and a click made on it
 * (or an item looked for in it) disagrees with the server, which answers with the whole inventory (again late, again
 * overwriting newer changes). So for about a round trip after a click changed a slot's contents, nothing is placed
 * from it or looked for in it; and after a hotbar slot was clicked or placed from, nothing is moved into it. Without
 * lag that's a tick, as before.
 */
final class SlotsInFlight {
	/** Ticks of a round trip at most: past that the server has answered whatever it will. */
	private static final int MAX_WINDOW = 20;

	/** By inventory index (0-40, see {@link Slots}). */
	private final long[] changed = new long[Slots.OFF_HAND + 1], used = new long[Slots.OFF_HAND + 1];

	SlotsInFlight() {
		java.util.Arrays.fill(changed, Long.MIN_VALUE / 2);
		java.util.Arrays.fill(used, Long.MIN_VALUE / 2);
	}

	/** A click changed what inventory slot {@code index} holds. */
	void changed(int index) {
		if (index >= 0 && index < changed.length) changed[index] = Ticks.current();
	}

	/** Something was placed or used from inventory slot {@code index}. */
	void used(int index) {
		if (index >= 0 && index < used.length) used[index] = Ticks.current();
	}

	/** Whether what slot {@code index} shows is what the server has, for placing from it or finding something in it. */
	boolean settled(int index) {
		return index < 0 || index >= changed.length || Ticks.current() - changed[index] >= roundTrip();
	}

	/** Whether a click may change slot {@code index} now: nothing done with it is still on its way to the server. */
	boolean swappable(int index) {
		return settled(index) && (index < 0 || index >= used.length || Ticks.current() - used[index] >= roundTrip());
	}

	/**
	 * A round trip in ticks, as long as the server takes to answer what you do: the slowest of the last few, as with
	 * jitter the median is often beaten by an answer still on its way. At least one.
	 */
	static int roundTrip() {
		return Math.clamp((MyriadImpl.get().serverStatsTracker().slowestRoundTrip() + 49) / 50, 1, MAX_WINDOW);
	}
}
