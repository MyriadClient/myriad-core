package dev.myriadclient.impl.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the inventory shows while modules move things they'll put back: every such move is a swap of two slots, kept in
 * the order made, and a slot shows what was in it before them, wherever they took that. Exact (the stack shown is the one
 * that's there now, its count as it is), without guessing by kind or waiting on time: a swap shows until the swap that
 * puts it back. An owner's swaps that come back to where they started are dropped; {@link #forget} drops an owner's
 * swaps when it gives up on putting things back.
 * <p>
 * Slots are inventory indexes (0-35 hotbar and inventory, 36-39 armour, 40 the off hand). Pure, so it can be tested
 * without a game.
 */
final class Presentation {
	private record Swap(Object owner, int a, int b) {}

	private final List<Swap> swaps = new ArrayList<>();

	/** {@code owner} swapped slots {@code a} and {@code b} (to put them back later). */
	void swapped(Object owner, int a, int b) {
		if (a == b) return;
		swaps.add(new Swap(owner, a, b));
		if (identity(owner)) swaps.removeIf(s -> s.owner() == owner);
	}

	/** {@code owner} won't put back what it moved: what's there shows as it is. */
	void forget(Object owner) {
		swaps.removeIf(s -> s.owner() == owner);
	}

	boolean isEmpty() {
		return swaps.isEmpty();
	}

	/**
	 * The slot whose stack slot {@code index} shows, itself when nothing moved through it: where what was in {@code index}
	 * before the swaps went, following them in the order made.
	 */
	int source(int index) {
		for (Swap s : swaps) {
			if (index == s.a()) index = s.b();
			else if (index == s.b()) index = s.a();
		}
		return index;
	}

	/** Whether {@code owner}'s swaps, made one after another, leave every slot as it was. */
	private boolean identity(Object owner) {
		Map<Integer, Integer> at = new HashMap<>();
		for (Swap s : swaps) {
			if (s.owner() != owner) continue;
			int a = at.getOrDefault(s.a(), s.a()), b = at.getOrDefault(s.b(), s.b());
			at.put(s.a(), b);
			at.put(s.b(), a);
		}
		for (Map.Entry<Integer, Integer> e : at.entrySet()) if (!e.getKey().equals(e.getValue())) return false;
		return true;
	}
}
