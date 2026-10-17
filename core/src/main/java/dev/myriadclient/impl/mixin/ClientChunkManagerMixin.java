package dev.myriadclient.impl.mixin;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.ChunkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkManagerMixin {
	@Inject(method = "replaceWithPacketData", at = @At("RETURN"))
	private void myriad$loaded(CallbackInfoReturnable<LevelChunk> cir) {
		LevelChunk chunk = cir.getReturnValue();
		if (chunk != null && Myriad.isReady() && Myriad.events().hasListeners(ChunkEvent.Loaded.class)) Myriad.events().post(new ChunkEvent.Loaded(chunk));
	}

	@Inject(method = "drop", at = @At("HEAD"))
	private void myriad$unloaded(ChunkPos pos, CallbackInfo ci) {
		if (Myriad.isReady() && Myriad.events().hasListeners(ChunkEvent.Unloaded.class)) Myriad.events().post(new ChunkEvent.Unloaded(pos));
	}
}
