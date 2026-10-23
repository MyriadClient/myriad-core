package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.build.Target;
import dev.myriadclient.impl.MyriadImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A build's materials: the hotbar slot with the item a block needs, or one moved in from the inventory for it. Once no
 * build is running the hotbar is put back as it was ({@link HotbarRestore}).
 */
final class BuildMaterials {
	private final Minecraft mc = Minecraft.getInstance();
	private final BuildManager manager;
	/** The items this build has needed: the only ones it moves into the hotbar, and the only ones it moves aside there. */
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
		int from = Myriad.inventory().findInInventory(s -> s.is(item));
		if (from < 0) {
			// In the off hand for a tick after an air placement, until it's swapped back first thing next tick.
			return Myriad.placement().offHandSlot() >= 0 && mc.player.getOffhandItem().is(item) ? -1 : -2;
		}
		return borrow(from) ? -1 : -3;
	}

	/**
	 * Moves inventory slot {@code from} into the hotbar slot {@link #hotbarSlotToFill()} picks, if a move can go this
	 * tick. False if no hotbar slot may take it.
	 */
	private boolean borrow(int from) {
		// Not while a block is in the off hand: a click now would put the server back on your slot before the swap back.
		// Nor from a slot a click just changed, which may not hold what it shows yet.
		if (Myriad.placement().offHandSlot() >= 0) return true;
		if (MyriadImpl.get().inventoryManager() instanceof InventoryManager i && !i.settled(from)) return true;
		int into = hotbarSlotToFill();
		if (into < 0) return false;
		if (!(MyriadImpl.get().inventoryManager() instanceof InventoryManager inventory)) return false;
		if (manager.moveMaterial(() -> {
			inventory.buildMoves(into);
			return inventory.moveToHotbar(from, into);
		})) {
			materials.add(mc.player.getInventory().getItem(into).getItem());
			filledSlots.remove((Integer) into);
			filledSlots.add(into);
		}
		return true;
	}

	/**
	 * The hotbar slot a material is moved into: an empty one; else the one this build filled longest ago; else one
	 * holding another of the blocks it builds with; else any other, whose item goes back there when the build is done
	 * ({@link HotbarRestore}). Never the one you have selected or the server holds, so what you see in your hand doesn't
	 * change, nor one with something a module borrowed. -1 if there's none.
	 */
	private int hotbarSlotToFill() {
		var inv = mc.player.getInventory();
		int selected = inv.getSelectedSlot();
		InventoryManager inventory = MyriadImpl.get().inventoryManager();
		int server = inventory.serverSlot();
		for (int i = 0; i < 9; i++) if (free(i, selected, server, inventory) && inv.getItem(i).isEmpty()) return i;
		// Checked again: you may have put something of your own there since.
		for (int i : filledSlots) if (free(i, selected, server, inventory) && materials.contains(inv.getItem(i).getItem())) return i;
		for (int i = 8; i >= 0; i--) if (free(i, selected, server, inventory) && materials.contains(inv.getItem(i).getItem())) return i;
		for (int i = 8; i >= 0; i--) if (free(i, selected, server, inventory)) return i;
		return -1;
	}

	/**
	 * Not your selected slot or the one the server holds, nothing a module borrowed in it, and nothing done with it still
	 * on its way to the server.
	 */
	private static boolean free(int slot, int selected, int server, InventoryManager inventory) {
		return slot != selected && slot != server && !inventory.lent(slot) && inventory.swappable(slot);
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
		int from = Myriad.inventory().findInInventory(s -> target.preference(s) >= 0);
		if (from < 0) return -2;
		return borrow(from) ? -1 : -3;
	}
}
