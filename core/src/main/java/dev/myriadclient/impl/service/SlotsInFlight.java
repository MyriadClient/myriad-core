package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.util.Ticks;

/**
 * Hotbar slots (and the off hand) the server may still be about to answer for. With lag, an inventory update the server
 * sent about a slot before it had the client's latest click there arrives after the client changed that slot, and shows
 * the old contents again until the next update: a placement made from that view places the wrong block, and a click
 * made on it disagrees with the server, which answers with the whole inventory (again late, again overwriting newer
 * changes). So for about a round trip after a click changed a slot's contents, nothing is placed from it; and after a
 * slot was clicked or placed from, nothing is moved into it. Without lag that's a tick, as before.
 */
final class SlotsInFlight {
	static final int OFF_HAND = 9;
	/** Ticks of a round trip at most: past that the server has answered whatever it will. */
	private static final int MAX_WINDOW = 20;

	private final long[] changed = new long[10], used = new long[10];

	SlotsInFlight() {
		java.util.Arrays.fill(changed, Long.MIN_VALUE / 2);
		java.util.Arrays.fill(used, Long.MIN_VALUE / 2);
	}

	/** A click changed what hotbar {@code slot} (0-8, or {@link #OFF_HAND}) holds. */
	void changed(int slot) {
		if (slot >= 0 && slot < changed.length) changed[slot] = Ticks.current();
	}

	/** Something was placed or used from hotbar {@code slot}. */
	void used(int slot) {
		if (slot >= 0 && slot < used.length) used[slot] = Ticks.current();
	}

	/** Whether what hotbar {@code slot} shows is what the server has, for placing from it. */
	boolean placeable(int slot) {
		return slot < 0 || slot >= changed.length || Ticks.current() - changed[slot] >= window();
	}

	/** Whether a click may change hotbar {@code slot} now: nothing done with it is still on its way to the server. */
	boolean swappable(int slot) {
		return placeable(slot) && (slot < 0 || slot >= used.length || Ticks.current() - used[slot] >= window());
	}

	/** A round trip in ticks, as long as the server takes to answer what you do (Server.ping); at least one. */
	private int window() {
		return Math.clamp((Myriad.server().ping() + 49) / 50, 1, MAX_WINDOW);
	}
}
