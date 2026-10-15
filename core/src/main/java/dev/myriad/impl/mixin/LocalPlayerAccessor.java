package dev.myriad.impl.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Whether the client last told the server it sprints: vanilla sends a start or stop just before each tick's movement
 * packet whenever sprinting differs from it, so setting it says "the server has a stop" (see InventoryManager's sprint
 * bracket).
 */
@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {
	@Accessor("wasSprinting")
	void myriad$setWasSprinting(boolean wasSprinting);
}
