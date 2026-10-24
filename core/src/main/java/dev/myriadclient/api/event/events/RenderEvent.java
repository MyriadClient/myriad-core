package dev.myriadclient.api.event.events;

import dev.myriadclient.api.render.RenderMode;
import dev.myriadclient.api.render.RenderLook;
import dev.myriadclient.api.world.ChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.ApiStatus;

/**
 * What your module shows in the world this frame: entities, boxes, blocks and a {@link ChunkCache}'s boxes, each in a
 * {@link RenderLook} (a module's {@code RenderSettings}, from its Render options) and a colour. Core draws them in the
 * {@link RenderMode} the player picked, as shader highlights (outlines, glow and fills around their exact shape;
 * touching ones that look alike share an outline) or as boxes (edges and faces, never merged), so a module never draws
 * twice or asks which mode is on. Depth is core's to get right in both: a box on a block's face doesn't flicker against
 * it, and what's hidden behind the world stays hidden unless the look draws through walls.
 * <p>
 * Posted on the render thread once a frame, before entities are drawn; colours are ARGB (alpha fades the thing).
 * Anything else a module draws (tracers, labels, lines) goes on {@link Render3DEvent} as before. One instance is reused
 * every frame: don't keep it past the handler.
 *
 * <pre>{@code
 * @Subscribe(inGame = true)
 * private void onRender(RenderEvent e) {
 *     for (Entity entity : mc.level.entitiesForRendering()) {
 *         if (entity instanceof Monster) e.entity(entity, look, monsters.argb());
 *     }
 * }
 * }</pre>
 */
public final class RenderEvent {
	/** Where the requests go: core's renderer. */
	@ApiStatus.Internal
	public interface Sink {
		void entity(Entity entity, RenderLook look, int color);

		void box(AABB box, RenderLook look, int color, boolean below);

		void block(BlockPos pos, RenderLook look, int color, boolean below);

		void chunks(ChunkCache<?> cache);
	}

	private final Below below = new Below();
	private Sink sink;
	private RenderMode mode;
	private float tickDelta;

	@ApiStatus.Internal
	public RenderEvent() {
	}

	@ApiStatus.Internal
	public RenderEvent reset(Sink sink, RenderMode mode, float tickDelta) {
		this.sink = sink;
		this.mode = mode;
		this.tickDelta = tickDelta;
		return this;
	}

	/** The mode things are drawn in this frame (for what a module draws itself; the requests here need nothing). */
	public RenderMode mode() {
		return mode;
	}

	/** The partial tick the frame is drawn at. */
	public float tickDelta() {
		return tickDelta;
	}

	/**
	 * Shows {@code entity}: its exact shape (armour, held items, capes) as a shader highlight, its hitbox as a box. The
	 * first request for an entity wins, so a higher-priority handler's look is the one drawn.
	 */
	public void entity(Entity entity, RenderLook look, int color) {
		if ((color >>> 24) != 0) sink.entity(entity, look, color);
	}

	/** Shows {@code box} (world coordinates). Off-screen boxes are skipped, so pass everything in range. */
	public void box(AABB box, RenderLook look, int color) {
		if ((color >>> 24) != 0) sink.box(box, look, color, false);
	}

	/** Shows the block at {@code pos} by its shape (a chest's, a slab's; a full cube for one without). */
	public void block(BlockPos pos, RenderLook look, int color) {
		if ((color >>> 24) != 0) sink.block(pos, look, color, false);
	}

	/**
	 * Shows the boxes of a cache built with {@link ChunkCache.Builder#boxes}: its meshes stay on the GPU and are made
	 * again only when its chunks, its look or the mode change, so thousands of blocks cost about what a few do.
	 */
	public void chunks(ChunkCache<?> cache) {
		sink.chunks(cache);
	}

	/**
	 * The same, drawn under every other module's shapes in {@link RenderMode#SHADER}: for something often right in front
	 * of you (the block under the crosshair) that would otherwise hide the highlights behind it on screen.
	 */
	public Below below() {
		return below;
	}

	/** {@link RenderEvent#below()}. */
	public final class Below {
		private Below() {
		}

		public void box(AABB box, RenderLook look, int color) {
			if ((color >>> 24) != 0) sink.box(box, look, color, true);
		}

		public void block(BlockPos pos, RenderLook look, int color) {
			if ((color >>> 24) != 0) sink.block(pos, look, color, true);
		}
	}
}
