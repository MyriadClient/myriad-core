package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

/**
 * A block state is about to be drawn: cancel to leave it out of the chunk mesh (X-ray, hiding blocks). Asked once per
 * block when a chunk section is meshed, on the chunk builder threads, so handlers must be thread-safe and fast; the
 * answer is for every block of that state, not a position. Change what you hide, then {@link #reloadChunks()}.
 */
public final class BlockRenderEvent extends Cancellable {
	private final BlockState state;

	@ApiStatus.Internal
	public BlockRenderEvent(BlockState state) {
		this.state = state;
	}

	public BlockState state() {
		return state;
	}

	/**
	 * Meshes every chunk again (as F3+A does), so a change to what's hidden shows. Vanilla and Sodium both rebuild from
	 * this at the start of the next frame.
	 */
	public static void reloadChunks() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null && mc.levelExtractor != null) mc.levelExtractor.allChanged();
	}
}
