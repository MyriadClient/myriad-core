package dev.myriadclient.impl.mixin;

import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The state id a menu's clicks carry (one the server doesn't have makes it send the whole menu back, see MenuSync), and
 * whether a drag is under way (any click but its own steps ends it, see InventoryLedger).
 */
@Mixin(AbstractContainerMenu.class)
public interface AbstractContainerMenuAccessor {
	@Accessor("stateId")
	void myriad$setStateId(int stateId);

	@Accessor("quickcraftStatus")
	int myriad$quickcraftStatus();
}
