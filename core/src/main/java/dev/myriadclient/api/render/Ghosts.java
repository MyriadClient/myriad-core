package dev.myriadclient.api.render;

import dev.myriadclient.api.event.events.Render3DEvent;
import dev.myriadclient.api.Myriad;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.phys.Vec3;

/**
 * A living entity drawn again as a see-through copy of itself, wherever you like: its skin, armour, cape and elytra
 * faded to {@code alpha} (held items stay solid), posed as its render state says. For showing where something will be
 * (a goal, a planned position) as the thing itself.
 *
 * <pre>{@code
 * LivingEntityRenderState s = (LivingEntityRenderState) mc.getEntityRenderDispatcher().extractEntity(mc.player, e.tickDelta());
 * s.walkAnimationPos = ...; // pose it
 * Ghosts.submit(e, s, goal, 0.35f);
 * }</pre>
 */
public final class Ghosts {
	private Ghosts() {
	}

	/** Submits {@code state} at {@code feet} (world coordinates) this frame, faded to {@code alpha} (0-1). From a {@link Render3DEvent}. */
	public static void submit(Render3DEvent event, LivingEntityRenderState state, Vec3 feet, float alpha) {
		Myriad.render().ghost(event, state, feet, alpha, null, 0);
	}

	/**
	 * Like {@link #submit(Render3DEvent, LivingEntityRenderState, Vec3, float)}, highlighted in {@code style} and
	 * {@code color} (ARGB) as an entity would be: outlined, glowing or filled around its exact silhouette (armour, cape,
	 * held items included). The highlight starts the frame after the first one asked.
	 */
	public static void submit(Render3DEvent event, LivingEntityRenderState state, Vec3 feet, float alpha, HighlightStyle style, int color) {
		Myriad.render().ghost(event, state, feet, alpha, style, color);
	}
}
