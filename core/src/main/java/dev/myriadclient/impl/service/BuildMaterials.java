package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.build.Target;
import dev.myriadclient.impl.MyriadImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A build's materials: the hotbar slot with the item a block needs, or the block brought in from the inventory by a
 * number-key swap into a hotbar slot other than the one you hold, where it stays for the rest of the build (each kind
 * costs one click however often the build comes back to it, while the hotbar has room). Once no build is running the
 * inventory is put back as it was ({@link HotbarRestore}); meanwhile every slot shows what it held before
 * ({@link Masks}), so nothing seems to move, your hand included.
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
	 * The hotbar slot holding {@code item}; otherwise moves one in from the inventory (a few moves a tick for all
	 * builds, keys released first so the server takes the click) and returns the slot it went to, to place from in
	 * the same tick; -1 to wait for a move, -2 if there's none at all, -3 if no hotbar slot may take it.
	 */
	int slotFor(Item item) {
		materials.add(item);
		int memo = manager.slotThisTick(item);
		return memo != Integer.MIN_VALUE ? memo : manager.rememberSlot(item, findSlot(item));
	}

	/** A stack of {@code item} in the hotbar or inventory (the off hand's for a tick after an air placement), or null. */
	@Nullable ItemStack stackOf(Item item) {
		var inv = mc.player.getInventory();
		for (int i = 0; i < 36; i++) if (inv.getItem(i).is(item)) return inv.getItem(i);
		return mc.player.getOffhandItem().is(item) ? mc.player.getOffhandItem() : null;
	}

	private int findSlot(Item item) {
		int slot = Myriad.inventory().findInHotbar(s -> s.is(item));
		if (slot >= 0) return slot;
		int from = smallestStack(item);
		if (from < 0) {
			// In the off hand for a tick after an air placement, until it's swapped back first thing next tick.
			return Myriad.placement().offHandSlot() >= 0 && mc.player.getOffhandItem().is(item) ? -1 : -2;
		}
		return borrow(from);
	}

	/**
	 * The inventory slot (9-35) with the smallest stack of {@code item}, or -1: a stack the build moved back out of the
	 * hotbar is used up before another is started, so one stack goes at a time (and a stack left part-used is the only
	 * one), rather than every stack a little.
	 */
	private int smallestStack(Item item) {
		var inv = mc.player.getInventory();
		int best = -1;
		for (int i = 9; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(item) && (best < 0 || s.getCount() < inv.getItem(best).getCount())) best = i;
		}
		return best;
	}

	/**
	 * Moves inventory slot {@code from} into the hotbar slot {@link #hotbarSlotToFill()} picks, if a move can go this
	 * tick: that slot, -1 to wait for one, -3 if no hotbar slot may take it.
	 */
	private int borrow(int from) {
		// Not while a block is in the off hand: a click now would put the server back on your slot before the swap back.
		// Nor from a slot a click just changed, which may not hold what it shows yet.
		if (Myriad.placement().offHandSlot() >= 0) return -1;
		if (MyriadImpl.get().inventoryManager() instanceof InventoryManager i && !i.settled(from)) return -1;
		int into = hotbarSlotToFill();
		if (into < 0) return -3;
		if (!(MyriadImpl.get().inventoryManager() instanceof InventoryManager inventory)) return -3;
		if (!manager.moveMaterial(() -> {
			inventory.buildMoves(into, from);
			// By pick-from-block if a block of it is in reach (the build's own, often): no click (see PickSwap).
			boolean pick = inventory.pickSwap().arm(from, into);
			if (!inventory.moveToHotbar(from, into)) {
				if (pick) inventory.pickSwap().disarm();
				return false;
			}
			if (pick) inventory.pickSwap().sent();
			inventory.buildMoved(into, from);
			return true;
		})) return -1;
		materials.add(mc.player.getInventory().getItem(into).getItem());
		filledSlots.remove((Integer) into);
		filledSlots.add(into);
		return into;
	}

	/**
	 * The hotbar slot a material is moved into: an empty one; else one with something of yours (it goes back there when
	 * the build is done, {@link HotbarRestore}), so the hotbar fills with the build's blocks; else the block this build
	 * moved in longest ago. Never the one you have selected or the server holds, so what's in your hand doesn't change,
	 * nor one with something a module borrowed. -1 if there's none.
	 */
	private int hotbarSlotToFill() {
		var inv = mc.player.getInventory();
		int selected = inv.getSelectedSlot();
		InventoryManager inventory = MyriadImpl.get().inventoryManager();
		int server = inventory.serverSlot();
		for (int i = 0; i < 9; i++) if (free(i, selected, server, inventory) && inv.getItem(i).isEmpty()) return i;
		// Your own items before the build's: each of those moved aside stays aside till the end, while a block moved out
		// for another may be wanted again (one click each way). Checked again: you may have put something there since.
		for (int i = 8; i >= 0; i--) if (free(i, selected, server, inventory) && !materials.contains(inv.getItem(i).getItem())) return i;
		for (int i : filledSlots) if (free(i, selected, server, inventory) && materials.contains(inv.getItem(i).getItem())) return i;
		for (int i = 8; i >= 0; i--) if (free(i, selected, server, inventory)) return i;
		return -1;
	}

	/**
	 * Not your selected slot or the one the server holds, nothing a module borrowed in it, nothing done with it still
	 * on its way to the server, and no placement from it waiting for its look.
	 */
	private boolean free(int slot, int selected, int server, InventoryManager inventory) {
		return slot != selected && slot != server && !inventory.lent(slot) && inventory.swappable(slot) && !manager.placement().queuedFrom(slot);
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
		return borrow(from);
	}
}
