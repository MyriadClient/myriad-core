package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.EntityRenderEvent;
import dev.myriadclient.impl.render.HighlightRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	/**
	 * Through-walls highlights keep entities that occlusion culling (Sodium, EntityCulling) would skip, and
	 * EntityRenderEvent.Visible hides entities.
	 */
	@ModifyReturnValue(method = "shouldRender", at = @At("RETURN"))
	private boolean myriad$visible(boolean original, Entity entity, Frustum frustum, double camX, double camY, double camZ) {
		boolean shown = HighlightRenderer.INSTANCE.isArmed() ? HighlightRenderer.INSTANCE.shouldRender(entity, original, frustum, camX, camY, camZ) : original;
		if (!shown || !Myriad.isReady() || !Myriad.events().hasListeners(EntityRenderEvent.Visible.class)) return shown;
		return !Myriad.events().post(new EntityRenderEvent.Visible(entity)).isCancelled();
	}
}
