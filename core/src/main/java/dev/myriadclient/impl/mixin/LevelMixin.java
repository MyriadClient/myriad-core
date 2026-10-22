package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.myriadclient.impl.event.Profiler;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Level.class)
public abstract class LevelMixin {
	/**
	 * Counts a block read for the profiler's hint at a chunk cache. Every read on every thread passes here, so it's a
	 * field read and a compare while not profiling (a return-value hook, so nothing is allocated per read).
	 */
	@ModifyReturnValue(method = "getBlockState", at = @At("RETURN"))
	private BlockState myriad$countRead(BlockState state) {
		Profiler.blockRead();
		return state;
	}
}
