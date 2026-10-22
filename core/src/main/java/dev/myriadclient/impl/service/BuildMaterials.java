package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.build.Target;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A build's materials: the hotbar slot with the item a block needs, or one moved in from the inventory for it. */
final class BuildMaterials {
	private final Minecraft mc = Minecraft.getInstance();
	private final BuildManager manager;
	/** The items exact targets have needed: the only ones moved into the hotbar for them, and the only ones moved out. */
	private final Set<Item> materials = new HashSet<>();
	/** Hotbar slots this build moved materials into, the longest ago first. */
	private final List<Integer> filledSlots = new ArrayList<>();

	BuildMaterials(BuildManager manager) {
		this.manager = manager;
	}

	/**
	 * The hotbar slot holding {@code item}; otherwise moves one in from the inventory (one move a tick for all builds,
	 * keys released first so the server takes the click) and returns -1 to wait for it; -2 if there's none at all, -3
	 * if no hotbar slot may take it.
	 */
	int slotFor(Item item) {
		materials.add(item);
		int memo = manager.slotThisTick(item);
		return memo != Integer.MIN_VALUE ? memo : manager.rememberSlot(item, findSlot(item));
	}

	private int findSlot(Item item) {
		int slot = Myriad.inventory().findInHotbar(s -> s.is(item));
		if (slot >= 0) return slot;
		if (Myriad.inventory().findInInventory(s -> s.is(item)) < 0) {
			// In the off hand for a tick after an air placement, until it's swapped back first thing next tick.
			return Myriad.placement().offHandSlot() >= 0 && mc.player.getOffhandItem().is(item) ? -1 : -2;
		}
		// Not while a block is in the off hand: a click now would put the server back on your slot before the swap back.
		if (Myriad.placement().offHandSlot() >= 0) return -1;
		int into = hotbarSlotToFill();
		if (into < 0) return -3;
		if (manager.moveMaterial(() -> Myriad.inventory().ensureInHotbar(s -> s.is(item), into) == into)) {
			filledSlots.remove((Integer) into);
			filledSlots.add(into);
		}
		return -1;
	}

	/**
	 * The hotbar slot a material is moved into: an empty one; else the one this build filled longest ago; else one
	 * holding another of the blocks it builds with. Never a slot with anything else: your own things stay where you keep
	 * them. -1 if there's none.
	 */
	private int hotbarSlotToFill() {
		var inv = mc.player.getInventory();
		for (int i = 0; i < 9; i++) if (inv.getItem(i).isEmpty()) return i;
		// Checked again: you may have put something of your own there since.
		for (int i : filledSlots) if (materials.contains(inv.getItem(i).getItem())) return i;
		for (int i = 8; i >= 0; i--) if (materials.contains(inv.getItem(i).getItem())) return i;
		return -1;
	}

	/** The hotbar slot with the best item for {@code target} (by its preference); otherwise moves one in, as {@link #slotFor(Item)}. */
	int slotFor(Target target) {
		var inv = mc.player.getInventory();
		int best = -1, bestPref = Integer.MAX_VALUE;
		for (int i = 0; i < 9; i++) {
			int pref = target.preference(inv.getItem(i));
			if (pref >= 0 && pref < bestPref) {
				best = i;
				bestPref = pref;
			}
		}
		if (best >= 0) return best;
		if (Myriad.inventory().findInInventory(s -> target.preference(s) >= 0) < 0) return -2;
		manager.moveMaterial(() -> Myriad.inventory().ensureInHotbar(s -> target.preference(s) >= 0, -1) >= 0);
		return -1;
	}
}
