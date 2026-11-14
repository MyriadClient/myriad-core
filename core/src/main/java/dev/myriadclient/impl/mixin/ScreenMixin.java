package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.ui.ThemedMenus;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Themed Screens on every vanilla screen through their common base: the theme's font while a screen lays out and
 * draws, the theme's background in place of the panorama and blur, and the disconnect screen's window. It also runs
 * Myriad's commands from clicked chat text, which vanilla only does for "/" ones.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {
	/** Themed Screens with Theme Font: the screen's text in the theme's font while it lays itself out and draws (see FontMixin). */
	@WrapMethod(method = "init(II)V")
	private void myriad$themedTextInit(int width, int height, Operation<Void> original) {
		ThemedMenus.withText((Screen) (Object) this, () -> original.call(width, height));
	}

	@WrapMethod(method = "resize(II)V")
	private void myriad$themedTextResize(int width, int height, Operation<Void> original) {
		ThemedMenus.withText((Screen) (Object) this, () -> original.call(width, height));
	}

	@WrapMethod(method = "extractRenderStateWithTooltipAndSubtitles")
	private void myriad$themedTextDraw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, Operation<Void> original) {
		ThemedMenus.withText((Screen) (Object) this, () -> original.call(graphics, mouseX, mouseY, a));
	}

	/** Themed Screens: the theme's background in place of the panorama, out of a world. */
	@Inject(method = "extractPanorama", at = @At("HEAD"), cancellable = true)
	private void myriad$themedPanorama(GuiGraphicsExtractor graphics, float a, CallbackInfo ci) {
		if (ThemedMenus.panorama(graphics, (Screen) (Object) this)) ci.cancel();
	}

	/** Themed Screens: not Minecraft's blur; over the world the theme's goes in with the menu's background (ThemedMenus.texture). */
	@Inject(method = "extractBlurredBackground", at = @At("HEAD"), cancellable = true)
	private void myriad$themedBlur(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		if (ThemedMenus.noBlur()) ci.cancel();
	}

	/** Themed Screens: the disconnect screen's title, reason and buttons on a window in the theme, over its background. */
	@Inject(method = "extractBackground", at = @At("TAIL"))
	private void myriad$messageWindow(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
		if ((Object) this instanceof DisconnectedScreen screen) ThemedMenus.window(graphics, ((DisconnectedScreenAccessor) screen).myriad$layout().getRectangle());
	}

	/** Vanilla only runs "/" commands from clicked chat text; run Myriad's (from Texts.command) too. */
	@Inject(method = "clickCommandAction", at = @At("HEAD"), cancellable = true)
	private static void myriad$runCommand(LocalPlayer player, String command, @Nullable Screen screenAfterCommand, CallbackInfo ci) {
		if (!Myriad.isReady() || MyriadImpl.get() == null) return;
		String prefix = Myriad.config().commandPrefix();
		if (prefix.isEmpty() || !command.startsWith(prefix)) return;
		MyriadImpl.get().commandManager().execute(command.substring(prefix.length()));
		ci.cancel();
	}
}
