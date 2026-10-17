package dev.myriadclient.impl.mixin;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.BlockUpdateEvent;
import dev.myriadclient.api.event.events.EntityEvent;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.network.PredictionAccess;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientWorldMixin implements PredictionAccess {
	@Shadow
	@Final
	private BlockStatePredictionHandler blockStatePredictionHandler;

	@Override
	public int myriad$currentSequence() {
		return blockStatePredictionHandler.currentSequence();
	}

	@Inject(method = "addEntity", at = @At("TAIL"))
	private void myriad$entityAdded(Entity entity, CallbackInfo ci) {
		if (Myriad.isReady() && Myriad.events().hasListeners(EntityEvent.Added.class)) Myriad.events().post(new EntityEvent.Added(entity));
	}

	@Inject(method = "removeEntity", at = @At("HEAD"))
	private void myriad$entityRemoved(int entityId, Entity.RemovalReason reason, CallbackInfo ci) {
		if (!Myriad.isReady() || !Myriad.events().hasListeners(EntityEvent.Removed.class)) return;
		Entity entity = ((ClientLevel) (Object) this).getEntity(entityId);
		if (entity != null) Myriad.events().post(new EntityEvent.Removed(entity, reason));
	}

	/** Every server block change (single and multi-block updates) arrives here. */
	@Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
	private void myriad$blockUpdate(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
		if (!Myriad.isReady() || !Myriad.events().hasListeners(BlockUpdateEvent.class)) return;
		BlockState old = ((ClientLevel) (Object) this).getBlockState(pos);
		Myriad.events().post(new BlockUpdateEvent(pos.immutable(), old, state));
	}

	/** The server answered block actions up to {@code sequence}; the world now holds its verdict for them. */
	@Inject(method = "handleBlockChangedAck", at = @At("TAIL"))
	private void myriad$blockAck(int sequence, CallbackInfo ci) {
		if (MyriadImpl.get() != null) MyriadImpl.get().blockAcks().onAck(sequence);
	}
}
