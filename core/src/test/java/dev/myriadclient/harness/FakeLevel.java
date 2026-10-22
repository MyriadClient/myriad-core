package dev.myriadclient.harness;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.HashMap;
import java.util.Map;

/**
 * The world as the harness's client sees it: blocks a test put there, air elsewhere, every chunk loaded. Made with
 * {@link Fields#allocate} (never by its constructor), so only what's overridden here works.
 */
public final class FakeLevel extends ClientLevel {
	private Map<BlockPos, BlockState> blocks;

	@SuppressWarnings("unused")
	private FakeLevel() {
		super(null, null, null, null, 0, 0, null, false, 0, 0);
	}

	static FakeLevel create() {
		FakeLevel level = Fields.allocate(FakeLevel.class);
		level.blocks = new HashMap<>();
		Fields.set(level, "isClientSide", true);
		return level;
	}

	/** Puts {@code state} at {@code pos} (air removes it). */
	public void set(BlockPos pos, BlockState state) {
		if (state.isAir()) blocks.remove(pos);
		else blocks.put(pos.immutable(), state);
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return getBlockState(pos).getFluidState();
	}

	@Override
	public boolean hasChunk(int chunkX, int chunkZ) {
		return true;
	}
}
