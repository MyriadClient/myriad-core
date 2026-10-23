package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.build.Target;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.service.PacketLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Items borrowed from the inventory into the hotbar ({@link InventoryManager#borrow}), and put back once nothing wants
 * them: one click a tick, at a moment the server takes it.
 */
final class Borrows {
	/** An item borrowed from {@code home} (9-35) into hotbar slot {@code slot}, to go back once it's no longer wanted. */
	static final class Borrow {
		final Object owner;
		final int home, slot, selectedBefore;
		/** What was borrowed, and what it made room for (now at home; null if the hotbar slot was empty). */
		final Item item, displaced;
		int lastWanted, waited, outOfPlace;
		boolean giveBack;

		Borrow(Object owner, int home, int slot, int selectedBefore, Item item, Item displaced, int now) {
			this.owner = owner;
			this.home = home;
			this.slot = slot;
			this.selectedBefore = selectedBefore;
			this.item = item;
			this.displaced = displaced;
			this.lastWanted = now;
		}
	}

	/** A borrowed item goes back once nothing has wanted it for this long. */
	private static final int KEEP_TICKS = 10;
	/** Ticks a return waits for a moment you aren't moving before releasing your keys for one. */
	private static final int WAIT_FOR_STILL = 40;
	/**
	 * Ticks the items may look out of place before a borrow is forgotten: an inventory update the server sent before it
	 * had the borrowing click can arrive after it, showing the old places until the next one.
	 */
	private static final int OUT_OF_PLACE_TICKS = 20;

	private final Minecraft mc = Minecraft.getInstance();
	private final InventoryManager inventory;
	private final InventoryClicks clicks;
	private final ClickGate gate;
	private final List<Borrow> borrows = new ArrayList<>();
	private int ticks;

	Borrows(InventoryManager inventory, InventoryClicks clicks, ClickGate gate) {
		this.inventory = inventory;
		this.clicks = clicks;
		this.gate = gate;
	}

	int borrow(Object owner, int inventoryIndex, Predicate<ItemStack> replaceable) {
		if (mc.player == null || inventoryIndex < 0 || inventoryIndex >= 36) return -1;
		if (inventoryIndex < 9) {
			Borrow b = at(inventoryIndex);
			if (b != null) {
				b.lastWanted = ticks;
				b.giveBack = false;
			}
			return inventoryIndex;
		}
		// Where it is now as the server has it: a click that just moved things there may not be answered yet.
		if (!inventory.settled(inventoryIndex) || !gate.prepareClick()) return -1;
		int to = roomFor(replaceable == null ? s -> false : replaceable);
		if (to < 0) return -1;
		var inv = mc.player.getInventory();
		Item item = inv.getItem(inventoryIndex).getItem();
		ItemStack displaced = inv.getItem(to);
		Item displacedItem = displaced.isEmpty() ? null : displaced.getItem();
		if (!clicks.moveToHotbar(inventoryIndex, to)) return -1;
		borrows.add(new Borrow(owner, inventoryIndex, to, inv.getSelectedSlot(), item, displacedItem, ticks));
		return to;
	}

	void giveBack(Object owner) {
		for (Borrow b : borrows) if (b.owner == owner) b.giveBack = true;
	}

	/**
	 * The hotbar slot to bring an item into: an empty one, else one {@code replaceable} accepts, else building blocks.
	 * Never the selected slot, the one the server holds, one with something borrowed or a build's material in it, or one
	 * a click or a use the server hasn't answered yet went to. -1 if none.
	 */
	int roomFor(Predicate<ItemStack> replaceable) {
		var inv = mc.player.getInventory();
		int selected = inv.getSelectedSlot(), to = -1, blocks = -1;
		for (int i = 0; i < 9; i++) {
			if (i == selected || i == inventory.serverSlot() || at(i) != null || inventory.buildUses(i) || !inventory.swappable(i)) continue;
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) return i;
			if (to < 0 && replaceable.test(s)) to = i;
			if (blocks < 0 && Target.solid().preference(s) >= 0) blocks = i;
		}
		return to >= 0 ? to : blocks;
	}

	/** The borrow in hotbar slot {@code hotbarSlot}, or null. */
	@Nullable Borrow at(int hotbarSlot) {
		for (Borrow b : borrows) if (b.slot == hotbarSlot) return b;
		return null;
	}

	/** The borrowed item (or nothing, if it was used up) is still in its slot, and what it displaced at home. */
	boolean intact(Borrow b) {
		var inv = mc.player.getInventory();
		ItemStack here = inv.getItem(b.slot), home = inv.getItem(b.home);
		return (here.isEmpty() || here.is(b.item)) && (b.displaced == null ? home.isEmpty() : home.is(b.displaced));
	}

	/**
	 * Puts back borrowed items nothing wants any more, one click a tick. A borrow stays while the server holds its slot
	 * (a hold, or a silent swap) or you're using it; one whose items have moved is forgotten where it is.
	 */
	@Subscribe(priority = 940)
	private void tick(TickEvent.Pre e) {
		ticks++;
		if (borrows.isEmpty() || mc.player == null) return;
		var inv = mc.player.getInventory();
		int selected = inv.getSelectedSlot();
		int serverSlot = inventory.serverSlot();
		for (Borrow b : new ArrayList<>(borrows)) {
			if (!intact(b)) {
				if (++b.outOfPlace > OUT_OF_PLACE_TICKS) borrows.remove(b);
				continue;
			}
			b.outOfPlace = 0;
			boolean held = serverSlot == b.slot && (inventory.isHolding() || serverSlot != selected);
			if (held || selected == b.slot && mc.player.isUsingItem()) b.lastWanted = ticks;
			if (held || !b.giveBack && ticks - b.lastWanted < KEEP_TICKS) continue;
			// Both slots as the server has them: nothing done with them still unanswered.
			if (!inventory.swappable(b.slot) || !inventory.settled(b.home)) continue;
			// A moment you aren't moving if one comes soon; otherwise your keys are released for a tick.
			if (!gate.safeToClick() && !(++b.waited > WAIT_FOR_STILL && gate.prepareClick())) continue;
			// With a container you opened yourself, through its menu (it shows your inventory too), rather than waiting for
			// it to close; one a module opened is left alone (its own clicks are under way there), so that waits.
			var menu = mc.player.containerMenu;
			boolean inContainer = menu != mc.player.inventoryMenu;
			if (inContainer && !Myriad.containers().current().map(v -> v.byHand()).orElse(false)) continue;
			if (inContainer ? !Myriad.limits().canSend(PacketLimits.Kind.INVENTORY, 1) || !gate.prepareClick() : !clicks.canClick(1)) continue;
			if (selected == b.slot && b.selectedBefore != b.slot) inventory.select(b.selectedBefore);
			if (inContainer && !clicks.swapIn(menu, b.home, b.slot)) continue;
			if (!inContainer) clicks.clickSwap(b.home, b.slot);
			borrows.remove(b);
			return;
		}
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		borrows.clear();
	}
}
