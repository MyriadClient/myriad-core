package dev.myriadclient.impl.mixin;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.ScreenOpenEvent;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Screens open through {@link Gui#setScreen}; ScreenOpenEvent lets modules cancel or swap them. */
@Mixin(Gui.class)
public abstract class GuiMixin {
	@Shadow
	public abstract void setScreen(Screen screen);

	@Unique
	private boolean myriad$replacingScreen;

	@Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
	private void myriad$onSetScreen(Screen screen, CallbackInfo ci) {
		if (myriad$replacingScreen || !Myriad.isReady()) return;
		ScreenOpenEvent event = Myriad.events().post(new ScreenOpenEvent(screen));
		if (event.isCancelled()) {
			ci.cancel();
		} else if (event.screen() != screen) {
			ci.cancel();
			myriad$replacingScreen = true;
			try {
				setScreen(event.screen());
			} finally {
				myriad$replacingScreen = false;
			}
		}
	}
}
