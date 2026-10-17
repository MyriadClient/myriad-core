package dev.myriadclient.impl.render;

import net.minecraft.world.entity.Entity;

/** Implemented on EntityRenderState by mixin so a state can be traced back to its entity. */
public interface EntityRenderStateAccess {
	Entity myriad$entity();

	void myriad$setEntity(Entity entity);
}
