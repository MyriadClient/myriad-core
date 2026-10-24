package dev.myriadclient.api.event.events;

import dev.myriadclient.api.render.HighlightStyle;
import dev.myriadclient.api.render.RenderMode;
import dev.myriadclient.api.render.RenderLook;
import dev.myriadclient.api.render.ShapeBuilder;
import dev.myriadclient.api.ui.widget.Preview;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link Preview} is being drawn (a Preview window, or one in a module's settings): show on its subject what your
 * module would show in the world, so the player sees a setting change as they make it. {@link #render} shows the
 * subject in your look in the {@link RenderMode} that's on, as {@code RenderEvent} does in the world; anything else
 * you draw goes on {@link #shapes()}, in the subject's coordinates ({@link #box()} is its box). Your hands are
 * highlighted through {@link HighlightEvent.Hand} as in the world.
 * <p>
 * Posted on the render thread, every frame, for each preview drawn. One instance is reused: don't keep it.
 *
 * <pre>{@code
 * @Subscribe
 * private void onPreview(PreviewEvent e) {
 *     if (e.block() != null && e.block().is(Blocks.CHEST)) e.render(look, color.argb());
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
	private boolean boxed;
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
		this.boxed = false;
		this.color = 0;
		return this;
	}

	public Preview.Subject subject() {
		return subject;
	}

	/**
	 * The entity shown (a mob, or a player looking as you do), or null. Never you yourself; placed where you are, so
	 * distance checks treat it as near.
	 */
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
	 * Shows the subject (an entity or a block) in {@code look}, in the mode that's on: a shader highlight around its
	 * shape, or a box around {@link #box()}. The first call wins.
	 */
	public void render(RenderLook look, int color) {
		if (shown() || (color >>> 24) == 0) return;
		style = look.style();
		this.color = color;
		if (RenderMode.current() == RenderMode.BOX) {
			look.box(shapes, box, color);
			// Taken (a fallback look stays off it), but already drawn.
			boxed = true;
		}
	}

	/** Whether a handler before this one showed it ({@link #render}), so a fallback look can stay off it. */
	public boolean shown() {
		return style != null;
	}

	/** The shader highlight asked for, if there's one to draw (a box from {@link #render} is drawn already). */
	@ApiStatus.Internal
	public @Nullable HighlightStyle style() {
		return boxed ? null : style;
	}

	@ApiStatus.Internal
	public int color() {
		return color;
	}
}
