package dev.myriadclient.impl.mixin.compat.entityculling;

import dev.myriadclient.impl.compat.EntityCullingCompat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * EntityCulling's tick culling gives an entity it traced as hidden only a bare tick (no animation, no smooth
 * movement), even one a through-walls highlight keeps drawn, which then jerked about on screen. Ahead of its hook
 * (a lower priority runs first), an entity kept drawn is marked not culled, so it gets its whole tick.
 */
@Mixin(value = ClientLevel.class, priority = 900)
public abstract class ClientLevelEntityCullingMixin {
	@Inject(method = "tickNonPassenger", at = @At("HEAD"))
	private void myriad$keepTicking(Entity entity, CallbackInfo ci) {
		EntityCullingCompat.keepTicking(entity);
	}
}
