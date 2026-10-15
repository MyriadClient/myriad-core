package dev.myriad.impl.mixin;

import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The state id a menu's clicks carry: one the server doesn't have makes it send the whole menu back (see MenuSync). */
@Mixin(AbstractContainerMenu.class)
public interface AbstractContainerMenuAccessor {
	@Accessor("stateId")
	void myriad$setStateId(int stateId);
}
