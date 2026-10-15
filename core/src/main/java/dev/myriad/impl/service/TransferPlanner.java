package dev.myriad.impl.service;

import net.minecraft.world.inventory.ContainerInput;
import org.jetbrains.annotations.Nullable;

/**
 * Plans the clicks that move items from one group of a menu's slots ({@code from}) to another ({@code to}), one click
 * at a time, from what the menu holds now. Each click is worked out after the last one has been applied (vanilla
 * predicts every click on the client at once), so the plan follows whatever the menu actually does.
 * <ul>
 *   <li>A whole stack that fits goes with one shift click, where a shift click lands in {@code to}.</li>
 *   <li>Otherwise the stack is picked up and the share wanted put down in {@code to}, onto stacks of the same item first.
 *   Part of a stack goes one item a click (a right click), whichever of the share or the rest is smaller, and the
 *   rest all at once (a left click): never more than half the stack plus two clicks.</li>
 *   <li>The cursor is empty again once a stack is dealt with.</li>
 * </ul>
 */
final class TransferPlanner {
	/** A menu as the planner sees it. Kinds: 0 for an empty slot, otherwise the same number for items that stack. */
	interface Grid {
		int kind(int slot);

		int count(int slot);

		/** The most items of {@code kind} the slot can hold; 0 if they can't go there. */
		int capacity(int slot, int kind);

		/** Whether the slot holds an item to move. */
		boolean wanted(int slot);

		/** Whether the slot's item can be taken out. */
		boolean mayPickup(int slot);

		int cursorKind();

		int cursorCount();
	}

	record Click(int slot, int button, ContainerInput input) {
		static Click left(int slot) {
			return new Click(slot, 0, ContainerInput.PICKUP);
		}

		static Click right(int slot) {
			return new Click(slot, 1, ContainerInput.PICKUP);
		}

		static Click shift(int slot) {
			return new Click(slot, 0, ContainerInput.QUICK_MOVE);
		}
	}

	private final int[] from, to;
	/** Whether a shift click on a {@code from} slot sends its stack into {@code to} (and nowhere else). */
	private final boolean shiftClicks;
	/** The stack on the cursor: the slot it was picked up from, and how many of it still go to {@code to}. */
	private int origin = -1, toPlace;

	TransferPlanner(int[] from, int[] to, boolean shiftClicks) {
		this.from = from;
		this.to = to;
		this.shiftClicks = shiftClicks;
	}

	/** Whether the cursor holds a stack this plan picked up. */
	boolean carrying() {
		return origin >= 0;
	}

	/**
	 * The next click, or null when there's nothing more to do: {@code remaining} items have gone (it's only read with
	 * the cursor empty), nothing left to move has room, or the cursor holds something this plan didn't pick up.
	 */
	@Nullable Click next(Grid g, int remaining) {
		int kind = g.cursorKind(), count = g.cursorCount();
		if (count <= 0) origin = -1;
		else if (origin < 0) return null;
		else return place(g, kind, count);
		if (remaining <= 0) return null;
		for (int s : from) {
			if (!g.wanted(s) || g.count(s) <= 0 || !g.mayPickup(s)) continue;
			int k = g.kind(s), n = g.count(s);
			int room = room(g, to, k);
			if (room <= 0) continue;
			int share = Math.min(n, Math.min(remaining, room));
			if (share == n && shiftClicks) return Click.shift(s);
			origin = s;
			toPlace = share;
			return Click.left(s);
		}
		return null;
	}

	/** What to do with the {@code count} items of {@code kind} on the cursor, picked up from {@link #origin}. */
	private @Nullable Click place(Grid g, int kind, int count) {
		if (toPlace > 0) {
			int target = target(g, to, kind, -1);
			if (target < 0) {
				// No room left where they go (the menu changed): the rest goes back.
				toPlace = 0;
			} else if (toPlace >= count) {
				toPlace -= Math.min(count, space(g, target, kind));
				return Click.left(target);
			} else if (toPlace <= count - toPlace) {
				toPlace--;
				return Click.right(target);
			} else {
				int back = target(g, from, kind, origin);
				if (back >= 0) return Click.right(back);
				// Nowhere to put one back: put down what's wanted, the rest after it.
				toPlace = Math.min(count, space(g, target, kind));
				return Click.left(target);
			}
		}
		int back = target(g, from, kind, origin);
		return back >= 0 ? Click.left(back) : null;
	}

	/** The slot among {@code slots} to put {@code kind} down on: {@code first} if it has room, else a stack of it with room, else an empty one; -1 if none. */
	private static int target(Grid g, int[] slots, int kind, int first) {
		if (first >= 0 && space(g, first, kind) > 0) return first;
		int empty = -1;
		for (int s : slots) {
			if (space(g, s, kind) <= 0) continue;
			if (g.kind(s) == kind) return s;
			if (empty < 0) empty = s;
		}
		return empty;
	}

	/** How many more of {@code kind} fit in {@code slot}. */
	private static int space(Grid g, int slot, int kind) {
		int k = g.kind(slot);
		if (k != 0 && k != kind) return 0;
		return Math.max(0, g.capacity(slot, kind) - (k == 0 ? 0 : g.count(slot)));
	}

	/** How many of {@code kind} fit among {@code slots}. */
	static int room(Grid g, int[] slots, int kind) {
		int room = 0;
		for (int s : slots) room += space(g, s, kind);
		return room;
	}
}
