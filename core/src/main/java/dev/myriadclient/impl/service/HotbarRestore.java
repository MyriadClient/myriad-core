package dev.myriadclient.impl.service;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.impl.MyriadImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;

/**
 * Puts the hotbar back the way it was before a build moved materials in: the build remembers what every inventory slot
 * held when it first moved something ({@link #begin}), and once it ends ({@link #finish}) each hotbar slot it used gets
 * its item back, one click each, all in one tick at a moment the server takes clicks, and what it brought in goes back to the slot it
 * came from where that's free. Worked out from where things are at the end rather than by undoing each move, since a
 * block moved aside is often brought back into another slot later, which an undo list can't follow. Meanwhile every slot
 * it touched, hotbar and inventory, shows what it held before (each move is a swap, see {@link Presentation}). Once the
 * last build has ended and the hotbar is back ({@link #resyncWhenDone}), the server is asked for the inventory as it has it, so anything the client got
 * wrong along the way is put right.
 * <p>
 * While a build runs, the slot you hold is yours: select one it moved something into and your item comes back at once
 * ({@link #giveBackHeld}), so what you hold is what the hotbar shows.
 */
final class HotbarRestore {
	/** Ticks a restore waits for a moment you aren't moving before releasing your keys for one. */
	private static final int WAIT_FOR_STILL = 10;
	/** Ticks a restore keeps trying before giving up (a slot whose item is gone is skipped at once). */
	private static final int GIVE_UP_TICKS = 600;

	private final Minecraft mc = Minecraft.getInstance();
	private final InventoryManager inventory;
	private final InventoryClicks clicks;
	private final ClickGate gate;
	private final SlotsInFlight inFlight;
	private final Presentation presentation;
	/** What each inventory slot (0-35) held before the build moved anything; null slots were empty. */
	private @Nullable Item[] before;
	/** Hotbar slots a build moved things into. */
	private final BitSet used = new BitSet(9);
	private boolean building, restoring, resync;
	private int waited, ticks;

	HotbarRestore(InventoryManager inventory, InventoryClicks clicks, ClickGate gate, SlotsInFlight inFlight, Presentation presentation) {
		this.inventory = inventory;
		this.clicks = clicks;
		this.gate = gate;
		this.inFlight = inFlight;
		this.presentation = presentation;
	}

	/**
	 * A build is about to move something into hotbar slot {@code hotbarSlot}. The first one since the hotbar was last
	 * as it should be remembers the inventory; a build starting while an earlier one's restore is under way takes that
	 * over, so the hotbar ends as it was before either.
	 */
	void begin(int hotbarSlot, int from) {
		if (before == null) {
			var inv = mc.player.getInventory();
			before = new Item[36];
			for (int i = 0; i < 36; i++) before[i] = inv.getItem(i).isEmpty() ? null : inv.getItem(i).getItem();
		}
		used.set(hotbarSlot);
		building = true;
		resync = false;
		restoring = false;
	}

	/**
	 * The last build has ended: once the hotbar is back (now, if nothing was moved), the inventory is resynced. A build
	 * starting before then calls it off ({@link #begin}).
	 */
	void resyncWhenDone() {
		resync = true;
		if (!restoring && !building) askForInventory();
	}

	private void askForInventory() {
		resync = false;
		if (mc.player != null) MyriadImpl.get().menuSync().refresh(mc.player.inventoryMenu);
	}

	/** The builds are done with the hotbar: put it back. */
	void finish() {
		if (!building) return;
		building = false;
		restoring = true;
		waited = 0;
		ticks = 0;
	}

	@Subscribe(priority = 940)
	private void tick(TickEvent.Pre e) {
		if (building && mc.player != null && before != null) giveBackHeld();
		if (!restoring || mc.player == null || before == null) return;
		if (++ticks > GIVE_UP_TICKS) {
			done();
			return;
		}
		// Only from your own inventory screen (or none), within the click budget, at a moment the server takes clicks. Not
		// while an air placement's block is in the off hand: the slot the hotbar shows isn't what it holds until it's back.
		if (mc.player.containerMenu != mc.player.inventoryMenu || inventory.undoPending() || inventory.restorePending()) return;
		var inv = mc.player.getInventory();
		boolean left = false, clicked = false;
		for (int s = used.nextSetBit(0); s >= 0; s = used.nextSetBit(s + 1)) {
			if (same(inv.getItem(s), before[s])) {
				used.clear(s);
				continue;
			}
			// Something borrowed is in it (a module's): it goes back first, then this.
			if (inventory.lent(s)) continue;
			int m = source(s);
			if (m < 0) {
				// What was there is gone (used up, dropped): leave the slot as it is.
				used.clear(s);
				continue;
			}
			left = true;
			// Every slot whose pair is free in this tick: both slots' last change (and the hotbar slot's last use)
			// answered, so a slot swapped just now waits (a click on an old view would disagree with the server).
			if (!inFlight.swappable(s) || !(m < 9 ? inFlight.swappable(m) : inFlight.settled(m))) continue;
			if (!clicked && !gate.safeToClick() && !(++waited > WAIT_FOR_STILL && gate.prepareClick())) return;
			if (!clicks.canClick(1)) return;
			clicks.clickSwap(m, s);
			presentation.swapped(this, m, s);
			clicked = true;
		}
		if (!left) done();
	}

	/**
	 * You selected a hotbar slot the build moved something into: your item goes back there now, one click, and what the
	 * build had there goes to where your item was (it can bring that into another slot). Not while a placement from the
	 * slot is still waiting to go out, or while something a module lent is in it.
	 */
	private void giveBackHeld() {
		var inv = mc.player.getInventory();
		int s = inv.getSelectedSlot();
		if (!used.get(s) || same(inv.getItem(s), before[s]) || inventory.lent(s)) return;
		if (mc.player.containerMenu != mc.player.inventoryMenu || inventory.undoPending() || inventory.restorePending()) return;
		if (MyriadImpl.get().building() instanceof BuildManager b && b.placement().queuedFrom(s)) return;
		int m = source(s);
		if (m < 0) return;
		if (!gate.safeToClick() && !gate.prepareClick()) return;
		if (!clicks.canClick(1)) return;
		clicks.clickSwap(m, s);
		presentation.swapped(this, m, s);
	}

	/**
	 * The slot to swap with hotbar slot {@code s} next: an inventory slot (9-35), or another hotbar slot the build used
	 * that doesn't hold its own item (a build moves what it displaced along the hotbar too). Best one holding what
	 * {@code s} held (or empty, for a slot that was empty) whose own item is what's in {@code s} now, so both go home;
	 * else a hotbar slot holding what {@code s} held; else the home of what's in {@code s} now, while something else that
	 * was moved sits there (that one comes into {@code s} and goes home next); else any inventory slot holding what
	 * {@code s} held. Each swap puts at least one item where it was. -1 if there's none.
	 */
	private int source(int s) {
		var inv = mc.player.getInventory();
		Item here = inv.getItem(s).isEmpty() ? null : inv.getItem(s).getItem();
		int any = -1, home = -1, along = -1;
		for (int m = 0; m < 36; m++) {
			ItemStack there = inv.getItem(m);
			boolean hotbar = m < 9;
			if (hotbar && (m == s || !used.get(m) || inventory.lent(m) || same(there, before[m]))) continue;
			if (same(there, before[s])) {
				if (before[m] == here) return m;
				if (hotbar) {
					if (before[s] != null && along < 0) along = m;
				} else if (any < 0) {
					any = m;
				}
			} else if (!hotbar && here != null && before[m] == here && !same(there, before[m]) && home < 0) {
				home = m;
			}
		}
		return along >= 0 ? along : home >= 0 ? home : any;
	}

	/** Whether a build is running (not yet putting the hotbar back). */
	boolean building() {
		return building;
	}

	/** Whether a build moved something into hotbar slot {@code s} that hasn't gone back yet. */
	boolean uses(int s) {
		return before != null && used.get(s);
	}

	private static boolean same(ItemStack stack, @Nullable Item item) {
		return item == null ? stack.isEmpty() : stack.is(item);
	}

	private void done() {
		// Put back, or given up on: what's left out shows as it is.
		presentation.forget(this);
		restoring = false;
		before = null;
		used.clear();
		if (resync && !building) askForInventory();
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		building = false;
		resync = false;
		done();
	}
}
