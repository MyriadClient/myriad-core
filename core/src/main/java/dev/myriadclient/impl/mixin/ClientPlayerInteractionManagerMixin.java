package dev.myriadclient.impl.mixin;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.BlockBreakEvent;
import dev.myriadclient.api.event.events.BlockBrokenEvent;
import dev.myriadclient.api.event.events.InteractEvent;
import dev.myriadclient.api.event.events.ItemUseEvent;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.dev.PacketLog;
import dev.myriadclient.impl.service.RotationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class ClientPlayerInteractionManagerMixin {
	@Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void myriad$attackBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (Myriad.isReady() && Myriad.events().hasListeners(BlockBreakEvent.Start.class)
			&& Myriad.events().post(new BlockBreakEvent.Start(pos, direction)).isCancelled()) cir.setReturnValue(false);
	}

	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void myriad$interactBlock(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		if (Myriad.isReady() && Myriad.events().hasListeners(InteractEvent.Block.class)
			&& Myriad.events().post(new InteractEvent.Block(hand, hit)).isCancelled()) cir.setReturnValue(InteractionResult.FAIL);
	}

	@Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
	private void myriad$interactItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (Myriad.isReady() && Myriad.events().hasListeners(InteractEvent.Item.class)
			&& Myriad.events().post(new InteractEvent.Item(hand)).isCancelled()) cir.setReturnValue(InteractionResult.FAIL);
	}

	/** After the cancellable hook above, so only a use that goes ahead is marked (see RotationManager.usingItem). */
	@Inject(method = "useItem", at = @At("HEAD"))
	private void myriad$useItemStart(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (Myriad.isReady()) MyriadImpl.get().rotationManager().usingItem(hand);
	}

	@Inject(method = "useItem", at = @At("RETURN"))
	private void myriad$useItemEnd(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (Myriad.isReady()) MyriadImpl.get().rotationManager().usingItem(null);
	}

	@Inject(method = "interact", at = @At("HEAD"), cancellable = true)
	private void myriad$interactEntity(Player player, Entity entity, EntityHitResult hit, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (Myriad.isReady() && Myriad.events().hasListeners(InteractEvent.EntityTarget.class)
			&& Myriad.events().post(new InteractEvent.EntityTarget(hand, entity)).isCancelled()) cir.setReturnValue(InteractionResult.FAIL);
	}

	/** Your own click while a module holds a slot: the server gets your visible slot for it (see Inventory.hold). */
	@Inject(method = "ensureHasSentCarriedItem", at = @At("HEAD"))
	private void myriad$userSlot(CallbackInfo ci) {
		if (Myriad.isReady()) MyriadImpl.get().inventoryManager().beforeCarriedSync();
	}

	/** Counts every click made, whichever way it then reaches the server (see PacketLimiter.recordClick). */
	@Inject(method = "handleContainerInput", at = @At("HEAD"))
	private void myriad$countClick(int containerId, int slotNum, int buttonNum, ContainerInput input, Player player, CallbackInfo ci) {
		if (MyriadImpl.get() == null) return;
		MyriadImpl.get().packetLimiter().recordClick();
		MyriadImpl.get().inventoryManager().clickMade(containerId, slotNum, buttonNum, input);
		PacketLog.clicked(containerId, slotNum, buttonNum, input);
	}

	@Unique
	private BlockState myriad$breaking;

	@Inject(method = "destroyBlock", at = @At("HEAD"))
	private void myriad$beforeBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		Minecraft mc = Minecraft.getInstance();
		myriad$breaking = mc.level == null ? null : mc.level.getBlockState(pos);
	}

	@Inject(method = "destroyBlock", at = @At("RETURN"))
	private void myriad$afterBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		BlockState state = myriad$breaking;
		myriad$breaking = null;
		if (cir.getReturnValueZ() && state != null && Myriad.isReady()) Myriad.events().post(new BlockBrokenEvent(pos.immutable(), state));
	}

	@Inject(method = "releaseUsingItem", at = @At("HEAD"))
	private void myriad$stopUsing(Player player, CallbackInfo ci) {
		if (Myriad.isReady() && player.isUsingItem() && Myriad.events().hasListeners(ItemUseEvent.Stopped.class)) {
			Myriad.events().post(new ItemUseEvent.Stopped(player.getUseItem().copy()));
		}
	}

	@Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void myriad$progress(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (Myriad.isReady() && Myriad.events().hasListeners(BlockBreakEvent.Progress.class)
			&& Myriad.events().post(new BlockBreakEvent.Progress(pos, direction)).isCancelled()) cir.setReturnValue(false);
	}
}
