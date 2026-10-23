package dev.myriadclient.impl.service;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.util.Slots;
import dev.myriadclient.impl.util.Clock;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * What the inventory shows while modules move things (see {@code Inventory.mask}): masked slots show what they held when
 * masked, and a hotbar slot with something borrowed or a build's material in it shows what was there before
 * ({@link Borrows}, {@link HotbarRestore}).
 */
final class Masks {
	private static final class Mask {
		final Map<Integer, ItemStack> held = new HashMap<>();
		/**
		 * What each slot shows: a copy, the same one for as long as the item is unchanged (the hand would take a new
		 * stack for a new item), and kept while the item is in none of the slots (a swap's two slot updates can come a
		 * tick apart). Never the live stack: moving an item empties the stack it leaves.
		 */
		final Map<Integer, ItemStack> last = new HashMap<>();
		long untilMs;
		int armor;

		/** Whether {@code stack} is one of the masked items (by kind: its wear may have changed since). */
		boolean has(ItemStack stack) {
			for (ItemStack h : held.values()) if (!h.isEmpty() && ItemStack.isSameItem(h, stack)) return true;
			return false;
		}
	}

	private final Minecraft mc = Minecraft.getInstance();
	private final Borrows borrows;
	private final HotbarRestore restore;
	/** Display masks, by owner: what each masked slot held when masked, and until when. */
	private final Map<Object, Mask> masks = new IdentityHashMap<>();

	Masks(Borrows borrows, HotbarRestore restore) {
		this.borrows = borrows;
		this.restore = restore;
	}

	void mask(Object owner, long holdMs, int... inventoryIndexes) {
		if (mc.player == null) return;
		var inv = mc.player.getInventory();
		Mask m = masks.get(owner);
		if (m == null || Clock.millis() > m.untilMs) {
			m = new Mask();
			m.armor = mc.player.getArmorValue();
			masks.put(owner, m);
		}
		for (int i : inventoryIndexes) if (InventoryClicks.valid(i)) m.held.putIfAbsent(i, inv.getItem(i).copy());
		m.untilMs = Clock.millis() + holdMs;
	}

	void unmask(Object owner) {
		masks.remove(owner);
	}

	/** The live mask covering {@code index}, if any. */
	private Mask maskAt(int index) {
		if (masks.isEmpty()) return null;
		long now = Clock.millis();
		for (Mask m : masks.values()) if (now <= m.untilMs && m.held.containsKey(index)) return m;
		return null;
	}

	/** Whether a mask covers inventory slot {@code index} right now. */
	boolean masked(int index) {
		return maskAt(index) != null;
	}

	ItemStack shown(int index) {
		if (mc.player == null || !InventoryClicks.valid(index)) return ItemStack.EMPTY;
		var inv = mc.player.getInventory();
		ItemStack real = inv.getItem(index);
		// A build's material in a hotbar slot you don't hold shows what was there, through the build's own swaps too.
		if (Slots.isHotbar(index) && index != inv.getSelectedSlot()) {
			ItemStack was = restore.shown(index);
			if (was != null) return was;
		}
		Mask m = maskAt(index);
		if (m != null) {
			// Something from outside the group turned up here (a pickup): it shows as it is.
			if (!real.isEmpty() && !m.has(real)) return real;
			ItemStack was = m.held.get(index);
			if (was.isEmpty()) return ItemStack.EMPTY;
			ItemStack live = ItemStack.isSameItem(real, was) ? real : null;
			for (int i : m.held.keySet()) {
				if (live != null) break;
				ItemStack there = inv.getItem(i);
				if (ItemStack.isSameItem(there, was)) live = there;
			}
			ItemStack last = m.last.get(index);
			if (live == null) return last != null ? last : real;
			if (last == null || !ItemStack.matches(last, live)) m.last.put(index, last = live.copy());
			return last;
		}
		if (!Slots.isHotbar(index)) return real;
		Borrows.Borrow b = borrows.at(index);
		if (b == null || inv.getSelectedSlot() == index || !borrows.intact(b)) return real;
		return inv.getItem(b.home);
	}

	int shownArmor() {
		if (mc.player == null) return 0;
		for (int i = Slots.FEET; i <= Slots.HEAD; i++) {
			Mask m = maskAt(i);
			if (m != null) return m.armor;
		}
		return mc.player.getArmorValue();
	}

	/** What the local player's {@code slot} shows: for the hands, the selected slot or the off hand. */
	ItemStack shownEquipment(EquipmentSlot slot) {
		if (mc.player == null) return ItemStack.EMPTY;
		return switch (slot) {
			case MAINHAND -> shown(mc.player.getInventory().getSelectedSlot());
			case OFFHAND -> shown(Slots.OFF_HAND);
			case FEET, LEGS, CHEST, HEAD -> shown(Slots.FEET + slot.getIndex());
			default -> mc.player.getItemBySlot(slot);
		};
	}

	/** Masks that ran out are dropped, early in the tick (with the borrows' own tick). */
	@Subscribe(priority = 940)
	private void expire(TickEvent.Pre e) {
		if (masks.isEmpty()) return;
		long now = Clock.millis();
		masks.values().removeIf(m -> now > m.untilMs);
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		masks.clear();
	}
}
