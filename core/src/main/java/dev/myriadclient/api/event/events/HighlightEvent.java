package dev.myriadclient.api.event.events;

import dev.myriadclient.api.render.HighlightStyle;
import dev.myriadclient.api.render.WorldMesh;
import dev.myriadclient.impl.render.HighlightRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Highlights: outlines, glows and fills traced around the exact silhouette of entities and shapes, drawn by a
 * screen-space shader after the world. Listen for these events while your module wants to highlight something; while
 * nothing listens, highlighting costs nothing. Both are posted on the render thread, every frame.
 * <p>
 * Entities are drawn over shapes, so a wall of highlighted chests doesn't hide the highlighted players behind it, and
 * shapes added through {@link Shapes#below()} are drawn under the other shapes.
 * Highlights that look alike (the same {@link HighlightStyle} and colour) connect: touching ones get one outline around
 * them all, so a vein of ores or a double chest is one shape. Where highlights that look different touch, the one added
 * later outlines itself across the boundary; a low-priority listener runs later, so its highlights draw over others
 * (as a highlight of the block under the crosshair should).
 * <p>
 * Colours are ARGB; the alpha fades the whole highlight (handy for fade-ins). The look comes from a
 * {@link HighlightStyle}; {@link dev.myriadclient.api.render.HighlightSettings} gives modules the standard options for one.
 *
 * <pre>{@code
 * @Subscribe
 * private void onHighlight(HighlightEvent.Entity e) {
 *     if (e.entity() instanceof Player) e.highlight(settings.style(), 0xFFFF5555);
 * }
 * }</pre>
 */
public abstract class HighlightEvent {
	/**
	 * An entity is being prepared for drawing this frame: call {@link #highlight} to highlight it. The highlight covers
	 * everything drawn with the entity (armour, held items, capes); riders are asked about separately. The first
	 * listener to highlight an entity wins, so higher-priority listeners override lower ones.
	 * <p>
	 * Vanilla's glowing effect uses the same silhouettes: while anything listens for highlight events, glowing
	 * entities are drawn by Myriad's shader too (as a thin outline with a short glow in their team colour).
	 * <p>
	 * One instance is reused for every entity: don't keep it past the handler.
	 */
	public static final class Entity extends HighlightEvent {
		private net.minecraft.world.entity.Entity entity;
		private float tickDelta;
		private @Nullable HighlightStyle style;
		private int color;

		@ApiStatus.Internal
		public Entity() {
		}

		@ApiStatus.Internal
		public Entity reset(net.minecraft.world.entity.Entity entity, float tickDelta) {
			this.entity = entity;
			this.tickDelta = tickDelta;
			this.style = null;
			this.color = 0;
			return this;
		}

		public net.minecraft.world.entity.Entity entity() {
			return entity;
		}

		/** The partial tick the entity is drawn at. */
		public float tickDelta() {
			return tickDelta;
		}

		/** Highlights the entity in {@code color} (ARGB). Ignored if already highlighted or {@code color}'s alpha is 0. */
		public void highlight(HighlightStyle style, int color) {
			if (this.style != null || style == null || (color >>> 24) == 0) return;
			this.style = style;
			this.color = color;
		}

		public boolean isHighlighted() {
			return style != null;
		}

		@ApiStatus.Internal
		public @Nullable HighlightStyle style() {
			return style;
		}

		@ApiStatus.Internal
		public int color() {
			return color;
		}
	}

	/**
	 * Once per frame, after {@link Render3DEvent}: highlight shapes in the world (containers, blocks, areas). Shapes
	 * added by one call form one silhouette; touching ones that look alike share an outline, so a double chest or a
	 * cluster of ores is outlined as one shape.
	 * <p>
	 * One instance is reused every frame: don't keep it past the handler.
	 */
	public static final class Shapes extends HighlightEvent {
		private final boolean below;
		private float tickDelta;
		private @Nullable Shapes belowView;

		@ApiStatus.Internal
		public Shapes() {
			this(false);
		}

		private Shapes(boolean below) {
			this.below = below;
		}

		@ApiStatus.Internal
		public Shapes reset(float tickDelta) {
			this.tickDelta = tickDelta;
			return this;
		}

		public float tickDelta() {
			return tickDelta;
		}

		/**
		 * The same event for shapes on a layer of their own, drawn under everyone else's: for a highlight that mustn't
		 * hide the others where it overlaps them on screen (a highlight of the block you're looking at, in front of
		 * highlighted chests behind it). Shapes there connect only with each other. The layer costs nothing while
		 * nothing is added to it.
		 */
		public Shapes below() {
			if (below) return this;
			if (belowView == null) belowView = new Shapes(true);
			belowView.tickDelta = tickDelta;
			return belowView;
		}

		/** Highlights a box (world coordinates) in {@code color} (ARGB). Boxes off screen are skipped cheaply. */
		public void box(AABB box, HighlightStyle style, int color) {
			HighlightRenderer.INSTANCE.box(box, style, color, below);
		}

		/**
		 * Highlights the outline shape of the block at {@code pos} (a chest's smaller box, a slab, a stair's two boxes),
		 * or the full block if it has no shape.
		 */
		public void block(BlockPos pos, HighlightStyle style, int color) {
			HighlightRenderer.INSTANCE.block(pos, style, color, 0, below);
		}

		/**
		 * As {@link #block(BlockPos, HighlightStyle, int)}, with the shape grown by {@code grow} blocks on every side. A
		 * hair (0.002) puts it in front of other highlights of the same block, as a highlight of the block under the
		 * crosshair wants, instead of flickering against them where their faces meet.
		 */
		public void block(BlockPos pos, HighlightStyle style, int color, double grow) {
			HighlightRenderer.INSTANCE.block(pos, style, color, grow, below);
		}

		/**
		 * Highlights the filled faces (quads) of a {@link WorldMesh}, which stays on the GPU: for highlights of many
		 * blocks that change rarely, build a mesh when they change (or let a {@link dev.myriadclient.api.world.ChunkCache} do it
		 * per chunk, see its {@code highlight}) instead of adding thousands of blocks every frame. Lines in the mesh are
		 * ignored. Each vertex's colour picks a colour from {@code palette} by index: build the faces with
		 * {@link #paletteColor}{@code (i)}. Up to 256 colours.
		 */
		public void mesh(WorldMesh mesh, HighlightStyle style, int... palette) {
			HighlightRenderer.INSTANCE.mesh(mesh, style, palette, below);
		}

		/** The vertex colour that picks {@code index} from a mesh's palette (see {@link #mesh}). */
		public static int paletteColor(int index) {
			return 0xFF000000 | (index & 0xFF) << 16;
		}
	}
}
