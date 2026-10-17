package dev.myriadclient.impl.mixin;

import dev.myriadclient.impl.render.UiRenderer;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives the UI renderer a moment between the world and the GUI being drawn, to blur the scene for frosted windows. */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
	@Inject(method = "draw", at = @At("HEAD"))
	private void myriad$beforeDraw(CallbackInfo ci) {
		UiRenderer renderer = UiRenderer.peek();
		if (renderer != null) renderer.beforeGuiDraw();
	}
}
