package dev.myriadclient.impl.mixin;

import dev.myriadclient.impl.ui.ThemedMenus;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Themed Screens with Theme Font: a themed menu's text without Minecraft's drop shadow, as the theme's own text is. On the
 * theme's dark buttons the shadow is lighter than what's under it, a grey copy of every letter down and to the right.
 */
@Mixin(GuiTextRenderState.class)
public abstract class GuiTextRenderStateMixin {
	@Shadow
	@Final
	@Mutable
	private boolean dropShadow;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void myriad$noShadow(CallbackInfo ci) {
		if (ThemedMenus.themedText()) dropShadow = false;
	}
}
