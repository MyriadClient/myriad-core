package dev.myriadclient.api.util;

/**
 * Converting between player inventory indexes ({@code PlayerInventory.getStack(i)}) and screen handler slot ids
 * (what {@code clickSlot} takes), which number the same slots differently.
 *
 * <pre>
 * inventory index   0-8 hotbar   9-35 main   36-39 armour (feet..head)   40 off hand
 * player screen     36-44        9-35        8..5                        45
 * container screen  size+27..    size+0..    -                           -      (container slots come first)
 * </pre>
 */
public final class Slots {
	public static final int HOTBAR_START = 0, HOTBAR_END = 9, MAIN_START = 9, MAIN_END = 36, OFF_HAND = 40;
	public static final int FEET = 36, LEGS = 37, CHEST = 38, HEAD = 39;

	private Slots() {
	}

	public static boolean isHotbar(int inventoryIndex) {
		return inventoryIndex >= HOTBAR_START && inventoryIndex < HOTBAR_END;
	}

	/** Slot id in the player's own screen (no container open; sync id 0) for an inventory index. */
	public static int playerScreen(int inventoryIndex) {
		if (isHotbar(inventoryIndex)) return 36 + inventoryIndex;
		if (inventoryIndex < MAIN_END) return inventoryIndex;
		if (inventoryIndex <= HEAD) return 8 - (inventoryIndex - FEET);
		if (inventoryIndex == OFF_HAND) return 45;
		throw new IllegalArgumentException("Not an inventory index: " + inventoryIndex);
	}

	/**
	 * Slot id in a container screen whose own slots number {@code containerSize}, for a hotbar or main inventory
	 * index. Armour and the off hand aren't part of container screens.
	 */
	public static int containerScreen(int containerSize, int inventoryIndex) {
		if (isHotbar(inventoryIndex)) return containerSize + 27 + inventoryIndex;
		if (inventoryIndex < MAIN_END) return containerSize + inventoryIndex - MAIN_START;
		throw new IllegalArgumentException("Container screens only hold the hotbar and main inventory: " + inventoryIndex);
	}
}
