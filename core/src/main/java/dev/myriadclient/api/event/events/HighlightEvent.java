package dev.myriadclient.api.event.events;

import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.render.HighlightStyle;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Shader highlights outside {@link RenderEvent}: your first-person hand and held items ({@link Hand}), which have no
 * box and are highlighted by the shader in either render mode, and the {@link #ENTITY_HIGHLIGHT} hook, which has the
 * last word on how each entity is shown. What modules show in the world goes through {@link RenderEvent}.
 * <p>
 * Colours are ARGB; the alpha fades the whole highlight. The look is a {@link HighlightStyle}, usually a
 * {@code RenderSettings}' {@code style()}.
 *
 * <pre>{@code
 * @Subscribe
 * private void onHand(HighlightEvent.Hand e) {
 *     if (e.part() == HighlightEvent.Hand.Part.ITEM) e.highlight(look.style(), color.argb());
 * }
 * }</pre>
 */
public abstract class HighlightEvent {
	/** How an entity is highlighted this frame, for {@link #ENTITY_HIGHLIGHT}: a null style for not at all. */
	public record EntityHighlight(net.minecraft.world.entity.Entity entity, @Nullable HighlightStyle style, int color) {
	}

	/**
	 * How each entity shown through {@link RenderEvent#entity} ends up, in either render mode, after the modules had
	 * their say (the input): change the style or colour, or return a null style to not show it. In Box mode only the
	 * colour and whether it's shown count. Called on the render thread, every frame, for each entity shown: keep it quick.
	 */
	public static final Hook<EntityHighlight, EntityHighlight> ENTITY_HIGHLIGHT = Hook.of(
		"myriad:entity_highlight", "Entity highlight", "How each entity is highlighted.");

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
}
