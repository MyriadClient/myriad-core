package dev.myriadclient.api.event.events;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * The collision shape of a block, as the local player's movement sees it. Replace it to walk on what isn't solid
 * (water, lava, powder snow), keep out of what hurts (fire, cactus, berry bushes), or pass through blocks; only your
 * own movement changes. Posted for every block near you every tick, on the render thread, so keep handlers cheap and
 * don't keep {@link #pos()} (it may be reused). Not cancellable. Checking for listeners is a single flag read, so the
 * event costs nothing while no module listens.
 *
 * <pre>{@code
 * @Subscribe
 * private void onShape(CollisionShapeEvent e) {
 *     if (e.state().getFluidState().is(FluidTags.WATER) && !mc.player.isShiftKeyDown()) e.setShape(Shapes.block());
 * }
 * }</pre>
 */
public final class CollisionShapeEvent {
	private final BlockState state;
	private final BlockPos pos;
	private VoxelShape shape;

	/** Core's; only Myriad makes these. */
	@ApiStatus.Internal
	public CollisionShapeEvent(BlockState state, BlockPos pos, VoxelShape shape) {
		this.state = state;
		this.pos = pos;
		this.shape = shape;
	}

	/** The block whose shape is asked for. */
	public BlockState state() {
		return state;
	}

	/** Where it is; possibly a mutable position the game goes on changing, so copy it to keep it. */
	public BlockPos pos() {
		return pos;
	}

	/** The shape so far: the block's own, or what an earlier handler set. */
	public VoxelShape shape() {
		return shape;
	}

	/**
	 * Collide with {@code shape} instead ({@link Shapes#empty()} to pass through, {@link Shapes#block()} for a full
	 * block), relative to the block's corner; null counts as empty.
	 */
	public void setShape(@Nullable VoxelShape shape) {
		this.shape = shape == null ? Shapes.empty() : shape;
	}
}
