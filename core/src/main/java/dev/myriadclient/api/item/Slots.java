package dev.myriadclient.api.item;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * Converting between player inventory indexes ({@code Inventory.getItem(i)}) and menu slot ids (what
 * {@code MultiPlayerGameMode.handleContainerInput} takes), which number the same slots differently.
 *
 * <pre>
 * inventory index   0-8 hotbar   9-35 main   36-39 armour (feet..head)   40 off hand
 * player screen     36-44        9-35        8..5                        45
 * container screen  size+27..    size+0..    -                           -      (container slots come first)
 * </pre>
 * For a menu you didn't open yourself (another mod's, or one laid out differently), find slots by what they show
 * instead: {@link #menuSlot}, {@link #playerSlots}, {@link #containerSlots}.
 */
public final class Slots {
	/**
	 * Inventory index ranges, start inclusive and end exclusive: the hotbar is {@code 0..8}, the main inventory
	 * {@code 9..35}; {@code OFF_HAND} is the off hand's index, 40.
	 */
	public static final int HOTBAR_START = 0, HOTBAR_END = 9, MAIN_START = 9, MAIN_END = 36, OFF_HAND = 40;
	/** The armour slots' inventory indexes, feet (36) to head (39). */
	public static final int FEET = 36, LEGS = 37, CHEST = 38, HEAD = 39;
	/** The armour slots, head to feet, as the inventory screen shows them. */
	@ApiStatus.AvailableSince("2.0")
	public static final List<EquipmentSlot> ARMOR = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

	private Slots() {
	}

	/** Whether an inventory index is a hotbar slot (0-8). */
	public static boolean isHotbar(int inventoryIndex) {
		return inventoryIndex >= HOTBAR_START && inventoryIndex < HOTBAR_END;
	}

	/**
	 * Slot id in the player's own screen (no container open; sync id 0) for an inventory index (0-40); throws
	 * {@link IllegalArgumentException} for anything else.
	 */
	public static int playerScreen(int inventoryIndex) {
		if (inventoryIndex < 0) throw new IllegalArgumentException("Not an inventory index: " + inventoryIndex);
		if (isHotbar(inventoryIndex)) return 36 + inventoryIndex;
		if (inventoryIndex < MAIN_END) return inventoryIndex;
		if (inventoryIndex <= HEAD) return 8 - (inventoryIndex - FEET);
		if (inventoryIndex == OFF_HAND) return 45;
		throw new IllegalArgumentException("Not an inventory index: " + inventoryIndex);
	}

	/**
	 * Slot id in a container screen whose own slots number {@code containerSize}, for a hotbar or main inventory
	 * index. Armour and the off hand aren't part of container screens: those throw {@link IllegalArgumentException}.
	 */
	public static int containerScreen(int containerSize, int inventoryIndex) {
		if (isHotbar(inventoryIndex)) return containerSize + 27 + inventoryIndex;
		if (inventoryIndex < MAIN_END) return containerSize + inventoryIndex - MAIN_START;
		throw new IllegalArgumentException("Container screens only hold the hotbar and main inventory: " + inventoryIndex);
	}

	/** Whether {@code menu} is the one open (your own inventory's counts while no other is). */
	@ApiStatus.AvailableSince("2.0")
	public static boolean isOpen(AbstractContainerMenu menu) {
		var player = Minecraft.getInstance().player;
		return player != null && player.containerMenu == menu;
	}

	/**
	 * The slot id in {@code menu} showing your inventory index {@code index} (0-35; also 36-40 in your own inventory's
	 * menu), found by what each slot shows, so it works for any menu; -1 if the menu doesn't show it. In game only (it
	 * reads your inventory), like the two methods below.
	 */
	@ApiStatus.AvailableSince("2.0")
	public static int menuSlot(AbstractContainerMenu menu, int index) {
		var inventory = Minecraft.getInstance().player.getInventory();
		for (Slot s : menu.slots) if (s.container == inventory && s.getContainerSlot() == index) return s.index;
		return -1;
	}

	/**
	 * The slot ids in {@code menu} of your main inventory (indexes 9-35), then your hotbar (0-8) if {@code hotbar}, in
	 * index order; an entry is -1 where the menu doesn't show that index.
	 */
	@ApiStatus.AvailableSince("2.0")
	public static int[] playerSlots(AbstractContainerMenu menu, boolean hotbar) {
		int[] ids = new int[hotbar ? 36 : 27];
		int n = 0;
		for (int index = MAIN_START; index < MAIN_END; index++) ids[n++] = menuSlot(menu, index);
		if (hotbar) for (int index = HOTBAR_START; index < HOTBAR_END; index++) ids[n++] = menuSlot(menu, index);
		return ids;
	}

	/**
	 * The slot ids of {@code menu}'s own slots (a chest's, a furnace's): those that don't show your inventory and come
	 * before it, as every vanilla menu lays them out (slots another mod adds after yours aren't counted).
	 */
	@ApiStatus.AvailableSince("2.0")
	public static int[] containerSlots(AbstractContainerMenu menu) {
		var inventory = Minecraft.getInstance().player.getInventory();
		int end = menu.slots.stream().filter(s -> s.container == inventory).mapToInt(s -> s.index).findFirst().orElse(menu.slots.size());
		return menu.slots.stream().filter(s -> s.container != inventory && s.index < end).mapToInt(s -> s.index).toArray();
	}
}
