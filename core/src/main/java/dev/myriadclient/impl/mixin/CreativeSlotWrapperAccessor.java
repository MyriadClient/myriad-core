package dev.myriadclient.impl.mixin;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The inventory slot a creative Inventory tab's slot stands for: the tab's own has the menu's index as its container slot (see ScreenSlots). */
@Mixin(targets = "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen$SlotWrapper")
public interface CreativeSlotWrapperAccessor {
	@Accessor("target")
	Slot myriad$target();
}
