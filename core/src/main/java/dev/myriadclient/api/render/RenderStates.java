package dev.myriadclient.api.render;

import dev.myriadclient.api.Myriad;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Since 1.21.2, entity renderers only receive a snapshot ({@link EntityRenderState}) instead of the entity. Myriad
 * records which entity each snapshot came from, so mixins into renderers can still ask "who is this?" (to colour
 * friends, hide some entities, draw chams on players only, …).
 *
 * <pre>{@code
 * Entity entity = RenderStates.entity(state);
 * if (entity instanceof PlayerEntity p && Myriad.friends().isFriend(p)) ...
 * }</pre>
 */
public final class RenderStates {
	private RenderStates() {
	}

	/** The entity {@code state} was last built from, or null for states not made by an entity renderer. */
	public static @Nullable Entity entity(EntityRenderState state) {
		return Myriad.render().entity(state);
	}
}
