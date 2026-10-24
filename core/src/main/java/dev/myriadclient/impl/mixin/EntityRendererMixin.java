package dev.myriadclient.impl.mixin;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.EntityRenderEvent;
import dev.myriadclient.impl.render.EntityRenderStateAccess;
import dev.myriadclient.impl.render.HighlightRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity, S extends EntityRenderState> {
	@Shadow
	protected abstract AABB getBoundingBoxForCulling(T entity);

	/**
	 * Every render state is built here: remember the entity it was built from (see RenderStates), and point its outline
	 * colour at its highlight, if any (a RenderEvent request in Shader mode).
	 */
	@Inject(method = "createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;", at = @At("RETURN"))
	private void myriad$rememberEntity(T entity, float tickDelta, CallbackInfoReturnable<S> cir) {
		S state = cir.getReturnValue();
		((EntityRenderStateAccess) state).myriad$setEntity(entity);
		if (HighlightRenderer.INSTANCE.isArmed()) HighlightRenderer.INSTANCE.entity(state, entity, tickDelta, getBoundingBoxForCulling(entity));
	}

	/**
	 * EntityRenderEvent.Nametag: modules that draw their own tags hide vanilla's (the name and the score line under it).
	 * Hooked where the tag is filled in rather than in shouldShowName, which living entities override without calling
	 * up, so every renderer is covered.
	 */
	@Inject(method = "extractNameTags(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FDD)V", at = @At("RETURN"))
	private void myriad$nametag(T entity, S state, float partialTicks, double nameTagDistance, double belowNameDistance, CallbackInfo ci) {
		if (state.nameTag == null && state.scoreText == null) return;
		if (Myriad.isReady() && Myriad.events().hasListeners(EntityRenderEvent.Nametag.class)
			&& Myriad.events().post(new EntityRenderEvent.Nametag(entity)).isCancelled()) {
			state.nameTag = null;
			state.scoreText = null;
		}
	}
}
