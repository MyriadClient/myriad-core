package dev.myriadclient.impl.util;

import dev.myriadclient.impl.mixin.CreativeSlotWrapperAccessor;
import net.minecraft.world.inventory.Slot;

/**
 * The slot a screen's slot stands for. The creative inventory's Inventory tab shows your inventory's menu through slots
 * of its own, numbered as that menu is, so their container slot is the menu's index (5 for the helmet), not the
 * inventory's (39): read one through its target.
 */
public final class ScreenSlots {
	private ScreenSlots() {
	}

	public static Slot real(Slot slot) {
		return slot instanceof CreativeSlotWrapperAccessor wrapper ? wrapper.myriad$target() : slot;
	}
}
