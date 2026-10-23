package dev.myriadclient.impl.service;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.util.Slots;
import dev.myriadclient.impl.MyriadImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * Makes sure air placements leave the off hand as they found it, and that you don't see them use it. Each one swaps the
 * block into the off hand and back the next tick; the hands show what they held throughout. With lag, the server's
 * answers to a swap and its placement arrive after the swap back and show the block in the off hand again for a moment,
 * and one that's never corrected (a server that answers with the whole inventory at odd moments) leaves it showing
 * there. A swap back an anti-cheat dropped is worse: the block stays in the off hand on the server while the client
 * shows what was there (a totem you don't have). So once the last swap back of a run has had a round trip, the
 * inventory is asked for (one click that changes nothing), and if the block really is in the off hand it's swapped back
 * with what was there.
 */
final class OffHandWatch {
	/** Ticks a watch keeps going before it gives up (the server never answering, say). */
	private static final int GIVE_UP_TICKS = 200;
	/** How long the hands' mask lasts past each tick of the watch: it ends with it, or soon after if the game stops ticking. */
	private static final long MASK_MS = 250;

	private final Minecraft mc = Minecraft.getInstance();
	private final InventoryManager inventory;
	private final InventoryClicks clicks;
	private final Masks masks;
	private boolean watching, asked, answered, fixed;
	/** What the off hand held before the first swap. */
	private @Nullable Item expected;
	/** What air placements swapped into the off hand: the only items the watch swaps back out of it. */
	private final Set<Item> swappedIn = new HashSet<>();
	private int ticks, quiet;
	/** Counts asks, so the answer to one a later swap made stale is ignored. */
	private int asks;

	OffHandWatch(InventoryManager inventory, InventoryClicks clicks, Masks masks) {
		this.inventory = inventory;
		this.clicks = clicks;
		this.masks = masks;
	}

	/** An air placement is about to swap hotbar slot {@code hotbarSlot}'s block into the off hand. */
	void swapping(int hotbarSlot) {
		var p = mc.player;
		if (!watching) {
			watching = true;
			asked = answered = fixed = false;
			ticks = quiet = 0;
			expected = p.getOffhandItem().isEmpty() ? null : p.getOffhandItem().getItem();
			swappedIn.clear();
		}
		ItemStack block = p.getInventory().getItem(hotbarSlot);
		if (!block.isEmpty()) swappedIn.add(block.getItem());
		// The check starts over once this one is answered too: an ask made before it can't tell what it left, and the
		// updates it brings would make what came back stale.
		quiet = 0;
		asks++;
		asked = answered = fixed = false;
		masks.mask(this, MASK_MS, Slots.OFF_HAND, hotbarSlot);
	}

	/** You swapped your hands yourself, or moved things by hand: what's in the off hand is yours to say. */
	void yours() {
		if (watching) stop();
	}

	@Subscribe(priority = 940)
	private void tick(TickEvent.Pre e) {
		if (!watching) return;
		if (mc.player == null || mc.gui.screen() instanceof AbstractContainerScreen<?> || ++ticks > GIVE_UP_TICKS) {
			stop();
			return;
		}
		masks.mask(this, MASK_MS);
		// Still swapping, or a swap back not yet answered.
		if (inventory.undoPending()) {
			quiet = 0;
			return;
		}
		if (++quiet <= SlotsInFlight.roundTrip() + 1) return;
		// Asked for whatever the client shows: a dropped swap back leaves the client sure the block went back.
		if (!asked) {
			asked = true;
			int ask = ++asks;
			MyriadImpl.get().menuSync().refresh(mc.player.inventoryMenu).whenComplete((v, t) -> {
				if (ask == asks) answered = true;
			});
			return;
		}
		// The answer is what the server had after every swap: no air placement came since (it starts the check over).
		if (!answered) return;
		if (right()) {
			stop();
			return;
		}
		if (fixed) return;
		// Put back what air placement left there, if what was there is still about.
		Item now = mc.player.getOffhandItem().getItem();
		int home = expected == null ? emptySlot() : find(expected);
		if (!swappedIn.contains(now) || home < 0) {
			stop();
			return;
		}
		if (clicks.swapWithOffhand(home)) fixed = true;
	}

	private boolean right() {
		ItemStack off = mc.player.getOffhandItem();
		return expected == null ? off.isEmpty() : off.is(expected);
	}

	/** The inventory slot (0-35) holding {@code item}: the hotbar first. -1 if none. */
	private int find(Item item) {
		var inv = mc.player.getInventory();
		for (int i = 0; i < 36; i++) if (inv.getItem(i).is(item)) return i;
		return -1;
	}

	/** An empty inventory slot for what's in the off hand, when it was empty before. -1 if none. */
	private int emptySlot() {
		var inv = mc.player.getInventory();
		for (int i = 35; i >= 9; i--) if (inv.getItem(i).isEmpty()) return i;
		return -1;
	}

	private void stop() {
		watching = false;
		swappedIn.clear();
		masks.unmask(this);
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		watching = false;
		swappedIn.clear();
	}
}
