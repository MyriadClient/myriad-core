package dev.myriadclient.impl.service;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;

/**
 * Puts the hotbar back the way it was before a build moved materials in: the build remembers what every inventory slot
 * held when it first moved something ({@link #begin}), and once it ends ({@link #finish}) each hotbar slot it used gets
 * its item back, one click a tick at moments the server takes clicks, and what it brought in goes back to the slot it
 * came from where that's free. Worked out from where things are at the end rather than by undoing each move, since a
 * block moved aside is often brought back into another slot later, which an undo list can't follow.
 */
final class HotbarRestore {
	/** Ticks a restore waits for a moment you aren't moving before releasing your keys for one. */
	private static final int WAIT_FOR_STILL = 40;
	/** Ticks a restore keeps trying before giving up (a slot whose item is gone is skipped at once). */
	private static final int GIVE_UP_TICKS = 600;

	private final Minecraft mc = Minecraft.getInstance();
	private final InventoryClicks clicks;
	private final ClickGate gate;
	private final SlotsInFlight inFlight;
	/** What each inventory slot (0-35) held before the build moved anything; null slots were empty. */
	private @Nullable Item[] before;
	/** Hotbar slots a build moved things into. */
	private final BitSet used = new BitSet(9);
	private boolean building, restoring;
	private int waited, ticks;

	HotbarRestore(InventoryClicks clicks, ClickGate gate, SlotsInFlight inFlight) {
		this.clicks = clicks;
		this.gate = gate;
		this.inFlight = inFlight;
	}

	/**
	 * A build is about to move something into hotbar slot {@code hotbarSlot}. The first one since the hotbar was last
	 * as it should be remembers the inventory; a build starting while an earlier one's restore is under way takes that
	 * over, so the hotbar ends as it was before either.
	 */
	void begin(int hotbarSlot) {
		if (before == null) {
			var inv = mc.player.getInventory();
			before = new Item[36];
			for (int i = 0; i < 36; i++) before[i] = inv.getItem(i).isEmpty() ? null : inv.getItem(i).getItem();
		}
		used.set(hotbarSlot);
		building = true;
		restoring = false;
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
		if (!restoring || mc.player == null || before == null) return;
		if (++ticks > GIVE_UP_TICKS) {
			done();
			return;
		}
		var inv = mc.player.getInventory();
		int slot = -1, from = -1;
		for (int s = used.nextSetBit(0); s >= 0; s = used.nextSetBit(s + 1)) {
			if (same(inv.getItem(s), before[s])) {
				used.clear(s);
				continue;
			}
			int m = source(s);
			if (m < 0) {
				// What was there is gone (used up, dropped): leave the slot as it is.
				used.clear(s);
				continue;
			}
			slot = s;
			from = m;
			break;
		}
		if (slot < 0) {
			done();
			return;
		}
		// Only from your own inventory screen (or none), within the click budget, at a moment the server takes clicks.
		// The slot's last change or use answered first: a click on an old view would disagree with the server.
		if (mc.player.containerMenu != mc.player.inventoryMenu || !inFlight.swappable(slot)) return;
		if (!gate.safeToClick() && !(++waited > WAIT_FOR_STILL && gate.prepareClick())) return;
		if (!clicks.canClick(1)) return;
		clicks.clickSwap(from, slot);
	}

	/**
	 * The inventory slot (9-35) to swap with hotbar slot {@code s} next. Best one holding what {@code s} held (or empty,
	 * for a slot that was empty) whose own item is what's in {@code s} now, so both go home; else the home of what's in
	 * {@code s} now, while something else that was moved sits there (that one comes into {@code s} and goes home next);
	 * else any slot holding what {@code s} held. Each swap puts at least one item where it was. -1 if there's none.
	 */
	private int source(int s) {
		var inv = mc.player.getInventory();
		Item here = inv.getItem(s).isEmpty() ? null : inv.getItem(s).getItem();
		int any = -1, home = -1;
		for (int m = 9; m < 36; m++) {
			ItemStack there = inv.getItem(m);
			if (same(there, before[s])) {
				if (before[m] == here) return m;
				if (any < 0) any = m;
			} else if (here != null && before[m] == here && !same(there, before[m]) && home < 0) {
				home = m;
			}
		}
		return home >= 0 ? home : any;
	}

	private static boolean same(ItemStack stack, @Nullable Item item) {
		return item == null ? stack.isEmpty() : stack.is(item);
	}

	private void done() {
		restoring = false;
		before = null;
		used.clear();
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		building = false;
		done();
	}
}
