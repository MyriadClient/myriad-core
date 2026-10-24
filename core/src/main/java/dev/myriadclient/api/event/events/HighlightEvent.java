package dev.myriadclient.api.event.events;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.render.HighlightSettings;
import dev.myriadclient.api.render.HighlightStyle;
import dev.myriadclient.api.render.WorldMesh;
import dev.myriadclient.api.world.ChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Highlights: outlines, glows and fills traced around the exact silhouette of entities and shapes, drawn by a
 * screen-space shader after the world. Listen for these events while your module wants to highlight something; while
 * nothing listens, highlighting costs nothing. Both are posted on the render thread, every frame.
 * <p>
 * Entities are drawn over shapes, so a wall of highlighted chests doesn't hide the highlighted players behind it, and
 * shapes added through {@link Shapes#below()} are drawn under the other shapes. Your first-person hand and held items
 * ({@link Hand}) are highlighted over the hand, after all of those.
 * Highlights that look alike (the same {@link HighlightStyle} and colour) connect: touching ones get one outline around
 * them all, so a vein of ores or a double chest is one shape. Where highlights that look different touch, the one added
 * later outlines itself across the boundary; a low-priority listener runs later, so its highlights draw over others
 * (as a highlight of the block under the crosshair should).
 * <p>
 * Colours are ARGB; the alpha fades the whole highlight (handy for fade-ins). The look comes from a
 * {@link HighlightStyle}; {@link HighlightSettings} gives modules the standard options for one.
 *
 * <pre>{@code
 * @Subscribe
 * private void onHighlight(HighlightEvent.Entity e) {
 *     if (e.entity() instanceof Player) e.highlight(settings.style(), 0xFFFF5555);
 * }
 * }</pre>
 */
public abstract class HighlightEvent {
	/** How an entity is highlighted this frame, for {@link #ENTITY_HIGHLIGHT}: a null style for not at all. */
	public record EntityHighlight(net.minecraft.world.entity.Entity entity, @Nullable HighlightStyle style, int color) {
	}

	/**
	 * How each entity ends up highlighted, after the modules' {@link Entity} listeners had their say (the input): change
	 * the style or colour, highlight what nobody did, or return a null style to not highlight it. Called on the render
	 * thread, every frame, for each entity drawn while anything listens for highlights: keep it quick.
	 */
	public static final Hook<EntityHighlight, EntityHighlight> ENTITY_HIGHLIGHT = Hook.of(
		"myriad:entity_highlight", "Entity highlight", "How each entity is highlighted.");

	/**
	 * An entity is being prepared for drawing this frame: call {@link #highlight} to highlight it. The highlight covers
	 * everything drawn with the entity (armour, held items, capes); riders are asked about separately. The first
	 * listener to highlight an entity wins, so higher-priority listeners override lower ones.
	 * <p>
	 * Vanilla's glowing effect uses the same silhouettes: while anything listens for highlight events, glowing
	 * entities are drawn by Myriad's shader too (as a thin outline with a short glow in their team colour).
	 * <p>
	 * One instance is reused for every entity: don't keep it past the handler.
	 *
	 * @deprecated Show entities through {@link RenderEvent#entity}: drawn in the render mode the player picked (a shader
	 * highlight, or a box in Box mode). Removed in 1.0.
	 */
	@Deprecated(since = "0.11.0", forRemoval = true)
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
	 * A part of your first-person view model is being drawn this frame: call {@link #highlight} to highlight it. Hand
	 * highlights are drawn over the hand, after the world's highlights; nothing is ever in front of the hand, so a
	 * style's through walls makes no difference. Parts that look alike connect, so an arm and the item it holds
	 * highlighted alike get one outline around both. The first listener to highlight a part wins.
	 * <p>
	 * Vanilla only draws a bare arm while the main hand is empty (and around a held map); a hidden hand (a module
	 * hiding it) isn't asked about. One instance is reused for every part: don't keep it past the handler.
	 */
	public static final class Hand extends HighlightEvent {
		/** What's being drawn. */
		public enum Part {
			/** The bare arm. */
			ARM,
			/** The item held in the hand. */
			ITEM
		}

		private Part part;
		private InteractionHand hand;
		private HumanoidArm side;
		private ItemStack item;
		private float tickDelta;
		private @Nullable HighlightStyle style;
		private int color;

		@ApiStatus.Internal
		public Hand() {
		}

		@ApiStatus.Internal
		public Hand reset(Part part, InteractionHand hand, HumanoidArm side, ItemStack item, float tickDelta) {
			this.part = part;
			this.hand = hand;
			this.side = side;
			this.item = item;
			this.tickDelta = tickDelta;
			this.style = null;
			this.color = 0;
			return this;
		}

		public Part part() {
			return part;
		}

		/** The hand it's in: main or off. */
		public InteractionHand hand() {
			return hand;
		}

		/** The side of the screen it's drawn on (the main hand is on the right unless your main arm is the left). */
		public HumanoidArm side() {
			return side;
		}

		/** The held item; empty for {@link Part#ARM}. */
		public ItemStack item() {
			return item;
		}

		/** The partial tick the hand is drawn at. */
		public float tickDelta() {
			return tickDelta;
		}

		/** Highlights the part in {@code color} (ARGB). Ignored if already highlighted or {@code color}'s alpha is 0. */
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
	 *
	 * @deprecated Show boxes, blocks and chunk meshes through {@link RenderEvent}: drawn in the render mode the player
	 * picked. Removed in 1.0.
	 */
	@Deprecated(since = "0.11.0", forRemoval = true)
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
			Myriad.render().highlightBox(box, style, color, below);
		}

		/**
		 * Highlights the outline shape of the block at {@code pos} (a chest's smaller box, a slab, a stair's two boxes),
		 * or the full block if it has no shape.
		 */
		public void block(BlockPos pos, HighlightStyle style, int color) {
			Myriad.render().highlightBlock(pos, style, color, 0, below);
		}

		/**
		 * As {@link #block(BlockPos, HighlightStyle, int)}, with the shape grown by {@code grow} blocks on every side. A
		 * hair (0.002) puts it in front of other highlights of the same block, as a highlight of the block under the
		 * crosshair wants, instead of flickering against them where their faces meet.
		 */
		public void block(BlockPos pos, HighlightStyle style, int color, double grow) {
			Myriad.render().highlightBlock(pos, style, color, grow, below);
		}

		/**
		 * Highlights the filled faces (quads) of a {@link WorldMesh}, which stays on the GPU: for highlights of many
		 * blocks that change rarely, build a mesh when they change (or let a {@link ChunkCache} do it
		 * per chunk, see its {@code highlight}) instead of adding thousands of blocks every frame. Lines in the mesh are
		 * ignored. Each vertex's colour picks a colour from {@code palette} by index: build the faces with
		 * {@link #paletteColor}{@code (i)}. Up to 256 colours.
		 */
		public void mesh(WorldMesh mesh, HighlightStyle style, int... palette) {
			Myriad.render().highlightMesh(mesh, style, palette, below);
		}

		/** The vertex colour that picks {@code index} from a mesh's palette (see {@link #mesh}). */
		public static int paletteColor(int index) {
			return 0xFF000000 | (index & 0xFF) << 16;
		}
	}
}
