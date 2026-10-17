package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.MouseButtonEvent;
import dev.myriadclient.api.event.events.MouseLookEvent;
import dev.myriadclient.api.event.events.MouseScrollEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseMixin {
	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void myriad$onMouseButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (window != mc.getWindow().handle() || !Myriad.isReady()) return;
		if (Myriad.events().post(new MouseButtonEvent(info.button(), action, info.modifiers(), mc.gui.screen() != null)).isCancelled()) ci.cancel();
	}

	@WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void myriad$look(LocalPlayer player, double dx, double dy, Operation<Void> original) {
		if (Myriad.isReady() && Myriad.events().hasListeners(MouseLookEvent.class)) {
			MouseLookEvent e = Myriad.events().post(new MouseLookEvent(dx, dy));
			if (e.isCancelled()) return;
			dx = e.deltaX();
			dy = e.deltaY();
		}
		original.call(player, dx, dy);
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void myriad$onMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (window != mc.getWindow().handle() || !Myriad.isReady()) return;
		if (Myriad.events().post(new MouseScrollEvent(horizontal, vertical)).isCancelled()) ci.cancel();
	}
}
