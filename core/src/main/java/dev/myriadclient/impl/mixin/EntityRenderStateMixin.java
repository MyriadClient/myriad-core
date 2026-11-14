package dev.myriadclient.impl.mixin;

import dev.myriadclient.impl.render.EntityRenderStateAccess;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Gives every entity render state the entity it was built from (EntityRenderStateAccess, set by EntityRendererMixin),
 * as render code later only has the state.
 */
@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements EntityRenderStateAccess {
	@Unique
	private Entity myriad$entity;

	@Override
	public Entity myriad$entity() {
		return myriad$entity;
	}

	@Override
	public void myriad$setEntity(Entity entity) {
		myriad$entity = entity;
	}
}
