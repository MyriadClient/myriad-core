package dev.myriadclient.impl.mixin;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.ListenerFlag;
import dev.myriadclient.api.event.events.BlockRenderEvent;
import dev.myriadclient.api.event.events.CollisionShapeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {
	/** CollisionShapeEvent: the local player's view of block collisions. */
	@Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
		at = @At("RETURN"), cancellable = true)
	private void myriad$collisionShape(BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
		// Runs for every block near every entity every tick, on the client and the integrated server: one volatile read
		// decides whether anything else happens.
		if (!Myriad.isReady() || !CollisionFlag.LISTENING.isSet()) return;
		if (!(context instanceof EntityCollisionContext ec) || ec.getEntity() == null || ec.getEntity() != Minecraft.getInstance().player) return;
		CollisionShapeEvent event = Myriad.events().post(new CollisionShapeEvent((BlockState) (Object) this, pos, cir.getReturnValue()));
		if (event.shape() != cir.getReturnValue()) cir.setReturnValue(event.shape());
	}

	/** BlockRenderEvent: a hidden state has no model, which every chunk mesher (vanilla, Indigo) respects. */
	@Inject(method = "getRenderShape", at = @At("HEAD"), cancellable = true)
	private void myriad$renderShape(CallbackInfoReturnable<RenderShape> cir) {
		if (!Myriad.isReady() || !CollisionFlag.RENDER.isSet()) return;
		if (Myriad.events().post(new BlockRenderEvent((BlockState) (Object) this)).isCancelled()) cir.setReturnValue(RenderShape.INVISIBLE);
	}

	/** Created on first use, after Myriad is up (the mixin applies before). */
	private static final class CollisionFlag {
		static final ListenerFlag LISTENING = Myriad.events().flag(CollisionShapeEvent.class);
		static final ListenerFlag RENDER = Myriad.events().flag(BlockRenderEvent.class);
	}
}
