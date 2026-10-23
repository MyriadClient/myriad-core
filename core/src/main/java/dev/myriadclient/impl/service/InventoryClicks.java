package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.PacketLimits;
import dev.myriadclient.api.util.Slots;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Finding items and moving them with clicks in the player's own menu (for {@link InventoryManager}): every click waits
 * for a moment the server takes it ({@link ClickGate}) and for room in the packet budget, so none is refused and leaves
 * the client showing items where the server has none.
 */
final class InventoryClicks {
	private final Minecraft mc = Minecraft.getInstance();
	private boolean swapping;
	private final InventoryManager inventory;
	private final ClickGate gate;

	InventoryClicks(InventoryManager inventory, ClickGate gate) {
		this.inventory = inventory;
		this.gate = gate;
	}

	int findInHotbar(Predicate<ItemStack> predicate) {
		return find(predicate, 0, 9);
	}

	int findInInventory(Predicate<ItemStack> predicate) {
		return find(predicate, 9, 36);
	}

	/** What modules asked to be left alone, by owner. */
	private final Map<Object, Predicate<ItemStack>> spared = new IdentityHashMap<>();

	void spare(Object owner, @Nullable Predicate<ItemStack> spared) {
		if (spared == null) this.spared.remove(owner);
		else this.spared.put(owner, spared);
	}

	boolean isSpared(ItemStack stack) {
		if (spared.isEmpty() || stack.isEmpty()) return false;
		for (Predicate<ItemStack> p : spared.values()) if (p.test(stack)) return true;
		return false;
	}

	int bestInHotbar(ToDoubleFunction<ItemStack> score) {
		return best(score, 0, 9);
	}

	int bestInInventory(ToDoubleFunction<ItemStack> score) {
		return best(score, 0, 36);
	}

	private int best(ToDoubleFunction<ItemStack> score, int from, int to) {
		if (mc.player == null) return -1;
		int best = -1;
		double bestScore = 0;
		for (int i = from; i < to; i++) {
			ItemStack stack = mc.player.getInventory().getItem(i);
			if (isSpared(stack)) continue;
			double s = score.applyAsDouble(stack);
			if (s > bestScore) {
				bestScore = s;
				best = i;
			}
		}
		return best;
	}

	int count(Predicate<ItemStack> predicate) {
		if (mc.player == null) return 0;
		int n = 0;
		var inv = mc.player.getInventory();
		for (int i = 0; i < 36; i++) if (predicate.test(inv.getItem(i))) n += inv.getItem(i).getCount();
		if (predicate.test(inv.getItem(Slots.OFF_HAND))) n += inv.getItem(Slots.OFF_HAND).getCount();
		return n;
	}

	boolean moveToHotbar(int inventoryIndex, int hotbarSlot) {
		if (mc.player == null || hotbarSlot < 0 || hotbarSlot > 8 || inventoryIndex < 0 || inventoryIndex >= 36) return false;
		if (inventoryIndex == hotbarSlot) return true;
		if (!answered(inventoryIndex, hotbarSlot) || !canClick(1)) return false;
		return clickSwap(inventoryIndex, hotbarSlot);
	}

	int ensureInHotbar(Predicate<ItemStack> predicate, int preferredSlot) {
		int slot = findInHotbar(predicate);
		if (slot != -1) return slot;
		int from = findInInventory(predicate);
		if (from == -1) return -1;
		int to = preferredSlot;
		if (to < 0 || to > 8) {
			to = findInHotbar(ItemStack::isEmpty);
			if (to == -1) to = mc.player.getInventory().getSelectedSlot();
		}
		return moveToHotbar(from, to) ? to : -1;
	}


	/**
	 * True if the player's own screen is the one open to clicks, the packet budget has room for {@code clicks}, and the
	 * server would take a click now; if that's all that's missing, your keys are released for the next tick
	 * ({@link #prepareClick()}), so asking again then works. A refused click would leave the client showing items
	 * where the server has none.
	 */
	boolean canClick(int clicks) {
		return mc.player != null && mc.gameMode != null && mc.player.containerMenu == mc.player.inventoryMenu
			&& Myriad.limits().canSend(PacketLimits.Kind.INVENTORY, clicks) && gate.prepareClick();
	}

	/**
	 * Whether the server has answered the last click on each of these slots: with lag, an answer to an earlier click can
	 * still be on its way and show a slot's old contents, and a click made on that (to put a totem in the off hand that
	 * looks empty again, say) undoes what the server already did. A click on them waits until then (see SlotsInFlight).
	 */
	private boolean answered(int... indexes) {
		for (int i : indexes) if (!inventory.settled(i)) return false;
		return true;
	}

	static boolean valid(int index) {
		return index >= 0 && index <= Slots.OFF_HAND;
	}

	private void click(int index, int button, ContainerInput action) {
		mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, Slots.playerScreen(index), button, action, mc.player);
	}

	boolean move(int from, int to) {
		if (!valid(from) || !valid(to)) return false;
		if (from == to) return true;
		if (!answered(from, to) || !canClick(3)) return false;
		if (Slots.isHotbar(to)) return clickSwap(from, to);
		if (Slots.isHotbar(from)) return clickSwap(to, from);
		// Armour into its own empty slot: a shift click puts it there.
		if (to >= Slots.FEET && to <= Slots.HEAD && from < Slots.MAIN_END && quickMovesTo(from, to)) {
			click(from, 0, ContainerInput.QUICK_MOVE);
			return true;
		}
		// Pick up, put down (swapping with what's there), then put back whatever ended up on the cursor.
		click(from, 0, ContainerInput.PICKUP);
		click(to, 0, ContainerInput.PICKUP);
		if (!mc.player.containerMenu.getCarried().isEmpty()) click(from, 0, ContainerInput.PICKUP);
		return true;
	}

	boolean merge(int from, int to) {
		if (!valid(from) || !valid(to) || from == to) return false;
		var inv = mc.player == null ? null : mc.player.getInventory();
		if (inv == null || !ItemStack.isSameItemSameComponents(inv.getItem(from), inv.getItem(to)) || inv.getItem(from).isEmpty()) return false;
		if (!answered(from, to) || !canClick(3)) return false;
		// Pick the source up, drop it on the target (they combine), and put any remainder back.
		click(from, 0, ContainerInput.PICKUP);
		click(to, 0, ContainerInput.PICKUP);
		if (!mc.player.containerMenu.getCarried().isEmpty()) click(from, 0, ContainerInput.PICKUP);
		return true;
	}

	boolean clickSwap(int index, int hotbarSlot) {
		click(index, hotbarSlot, ContainerInput.SWAP);
		return true;
	}

	/** A number-key swap of inventory {@code index} with {@code hotbarSlot} in an open container's menu; false if it doesn't show that slot. */
	boolean swapIn(AbstractContainerMenu menu, int index, int hotbarSlot) {
		var inv = mc.player.getInventory();
		for (var slot : menu.slots) {
			if (slot.container != inv || slot.getContainerSlot() != index) continue;
			mc.gameMode.handleContainerInput(menu.containerId, slot.index, hotbarSlot, ContainerInput.SWAP, mc.player);
			return true;
		}
		return false;
	}

	/** Whether a shift click on {@code from} (outside the armour slots) lands the item in armour slot {@code to}. */
	private boolean quickMovesTo(int from, int to) {
		var inv = mc.player.getInventory();
		ItemStack stack = inv.getItem(from);
		if (stack.isEmpty() || !inv.getItem(to).isEmpty()) return false;
		EquipmentSlot slot = mc.player.getEquipmentSlotForItem(stack);
		return slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR && Slots.FEET + slot.getIndex() == to;
	}

	boolean swapWithOffhand(int inventoryIndex) {
		if (mc.player == null || !valid(inventoryIndex) || inventoryIndex == Slots.OFF_HAND || !answered(inventoryIndex, Slots.OFF_HAND)) return false;
		// From the hotbar while a click would have to wait: the swap-hands key with that slot held, which isn't a click.
		// Not after an attack or use this tick, which Grim (PacketOrderG) cancels it after.
		if (Slots.isHotbar(inventoryIndex) && !gate.safeToClick() && !inventory.actedThisTick() && mc.getConnection() != null && !mc.player.isSpectator()) {
			inventory.silentSwap(inventoryIndex, this::swapHands);
			return true;
		}
		if (!canClick(1)) return false;
		// Button 40 is the off hand swap key.
		click(inventoryIndex, 40, ContainerInput.SWAP);
		return true;
	}

	/** Swaps the main and off hand items, as the swap-hands key does, showing it at once. */
	void swapHands() {
		var p = mc.player;
		ItemStack off = p.getOffhandItem();
		p.setItemInHand(InteractionHand.OFF_HAND, p.getMainHandItem());
		p.setItemInHand(InteractionHand.MAIN_HAND, off);
		swapping = true;
		try {
			mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
		} finally {
			swapping = false;
		}
	}

	/** Whether the hand swap going out now is one of these (not your own key). */
	boolean swapping() {
		return swapping;
	}

	boolean quickMove(int inventoryIndex) {
		if (!valid(inventoryIndex) || !answered(inventoryIndex) || !canClick(1)) return false;
		click(inventoryIndex, 0, ContainerInput.QUICK_MOVE);
		return true;
	}

	boolean drop(int inventoryIndex, boolean wholeStack) {
		if (!valid(inventoryIndex) || !answered(inventoryIndex) || !canClick(1)) return false;
		click(inventoryIndex, wholeStack ? 1 : 0, ContainerInput.THROW);
		return true;
	}

	private int find(Predicate<ItemStack> predicate, int from, int to) {
		if (mc.player == null) return -1;
		for (int i = from; i < to; i++) if (predicate.test(mc.player.getInventory().getItem(i))) return i;
		return -1;
	}
}
