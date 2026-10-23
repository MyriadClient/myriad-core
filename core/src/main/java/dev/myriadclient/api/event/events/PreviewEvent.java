package dev.myriadclient.api.event.events;

import dev.myriadclient.api.render.HighlightStyle;
import dev.myriadclient.api.render.ShapeBuilder;
import dev.myriadclient.api.ui.widget.Preview;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link Preview} is being drawn (a Preview window, or one in a module's settings): show on its subject what your
 * module would show in the world, so the player sees a setting change as they make it. Entities and your hands are
 * highlighted through {@link HighlightEvent.Entity} and {@link HighlightEvent.Hand} as in the world, with nothing more
 * to do here; boxes and lines go on {@link #shapes()}, in the subject's coordinates ({@link #box()} is its box), and a
 * block's shader highlight through {@link #highlight}.
 * <p>
 * Posted on the render thread, every frame, for each preview drawn. One instance is reused: don't keep it.
 *
 * <pre>{@code
 * @Subscribe
 * private void onPreview(PreviewEvent e) {
 *     if (e.block() != null && e.block().is(Blocks.CHEST)) e.highlight(outline.style(), color.argb());
 * }
 * }</pre>
 */
public final class PreviewEvent {
	private Preview.Subject subject;
	private @Nullable Entity entity;
	private @Nullable BlockState block;
	private AABB box;
	private ShapeBuilder shapes;
	private @Nullable HighlightStyle style;
	private int color;

	@ApiStatus.Internal
	public PreviewEvent() {
	}

	@ApiStatus.Internal
	public PreviewEvent reset(Preview.Subject subject, @Nullable Entity entity, @Nullable BlockState block, AABB box, ShapeBuilder shapes) {
		this.subject = subject;
		this.entity = entity;
		this.block = block;
		this.box = box;
		this.shapes = shapes;
		this.style = null;
		this.color = 0;
		return this;
	}

	public Preview.Subject subject() {
		return subject;
	}

	/** The entity shown (a mob, or you), or null. Placed where you are, so distance checks treat it as near. */
	public @Nullable Entity entity() {
		return entity;
	}

	/** The block shown (from a block's item: a chest, a shulker box), or null. */
	public @Nullable BlockState block() {
		return block;
	}

	/** Whether it shows your first-person hands and held items. */
	public boolean viewModel() {
		return subject instanceof Preview.ViewModelSubject;
	}

	/** The subject's box in the preview's coordinates: an entity's hitbox from its feet, a block's cube from the origin. */
	public AABB box() {
		return box;
	}

	/** Boxes and lines drawn on the preview, in the same coordinates as {@link #box()}. */
	public ShapeBuilder shapes() {
		return shapes;
	}

	/**
	 * Highlights the block or item shown with the shader (outline, glow, fill), as {@link HighlightEvent.Shapes} does a
	 * block in the world. The first call wins. Entities and hands are asked through {@link HighlightEvent} instead.
	 */
	public void highlight(HighlightStyle style, int color) {
		if (this.style != null || style == null) return;
		this.style = style;
		this.color = color;
	}

	/** Whether a handler before this one highlighted it ({@link #highlight}), so a fallback look can stay off it. */
	public boolean highlighted() {
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
