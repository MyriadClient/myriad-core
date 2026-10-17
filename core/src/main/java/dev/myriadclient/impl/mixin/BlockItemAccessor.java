package dev.myriadclient.impl.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** What placing a block item would put down, as vanilla works it out (null if it can't be placed). */
@Mixin(BlockItem.class)
public interface BlockItemAccessor {
	@Invoker("getPlacementState")
	BlockState myriad$placementState(BlockPlaceContext context);
}
