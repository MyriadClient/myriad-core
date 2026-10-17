package dev.myriadclient.impl.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.WindowResizeEvent;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.service.InventoryManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {
	@Shadow
	@Final
	private Window window;

	/** Your own right clicks, so a module's slot hold steps aside for them (see Inventory.hold). */
	@Inject(method = "startUseItem", at = @At("HEAD"))
	private void myriad$useStart(CallbackInfo ci) {
		myriad$click(true);
	}

	@Inject(method = "startUseItem", at = @At("RETURN"))
	private void myriad$useEnd(CallbackInfo ci) {
		myriad$click(false);
	}

	@Unique
	private static void myriad$click(boolean active) {
		if (Myriad.isReady() && Myriad.inventory() instanceof InventoryManager inventory) inventory.userClick(active);
	}

	/** Your own attacks and mining, so a silent swap still waiting to go back does before them (see Inventory.silentSwap). */
	@Inject(method = "startAttack", at = @At("HEAD"))
	private void myriad$attackStart(CallbackInfoReturnable<Boolean> cir) {
		myriad$attack(true);
	}

	@Inject(method = "startAttack", at = @At("RETURN"))
	private void myriad$attackEnd(CallbackInfoReturnable<Boolean> cir) {
		myriad$attack(false);
	}

	@Inject(method = "continueAttack", at = @At("HEAD"))
	private void myriad$miningStart(boolean down, CallbackInfo ci) {
		myriad$attack(true);
	}

	@Inject(method = "continueAttack", at = @At("RETURN"))
	private void myriad$miningEnd(boolean down, CallbackInfo ci) {
		myriad$attack(false);
	}

	@Unique
	private static void myriad$attack(boolean active) {
		if (Myriad.isReady() && Myriad.inventory() instanceof InventoryManager inventory) inventory.userAttack(active);
	}

	@Inject(method = "resizeGui", at = @At("TAIL"))
	private void myriad$onResize(CallbackInfo ci) {
		if (Myriad.isReady()) Myriad.events().post(new WindowResizeEvent(window.getWidth(), window.getHeight()));
	}

	/** Tick speed: shortens (or stretches) the time the client waits between game ticks. */
	@Inject(method = "getTickTargetMillis", at = @At("RETURN"), cancellable = true)
	private void myriad$tickSpeed(float defaultMillis, CallbackInfoReturnable<Float> cir) {
		if (MyriadImpl.get() == null) return;
		float m = MyriadImpl.get().tickSpeedManager().multiplier();
		if (m != 1f) cir.setReturnValue(cir.getReturnValue() / m);
	}
}
