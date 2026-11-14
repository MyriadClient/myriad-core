package dev.myriadclient.impl.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** What the client last told the server, which vanilla compares against before each tick's movement packet. */
@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {
	/**
	 * Whether the client last told the server it sprints: vanilla sends a start or stop just before each tick's movement
	 * packet whenever sprinting differs from it, so setting it says "the server has a stop" (see InventoryManager's
	 * sprint bracket).
	 */
	@Accessor("wasSprinting")
	void myriad$setWasSprinting(boolean wasSprinting);

	/** The position last sent (see ClientPacketListenerMixin's teleports). */
	@Accessor("xLast")
	void myriad$setXLast(double x);

	@Accessor("yLast")
	void myriad$setYLast(double y);

	@Accessor("zLast")
	void myriad$setZLast(double z);
}
