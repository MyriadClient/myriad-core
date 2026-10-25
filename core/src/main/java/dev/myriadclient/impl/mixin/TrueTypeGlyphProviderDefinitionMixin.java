package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.myriadclient.impl.ui.ThemedText;
import net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** The theme's font (myriad's TTFs) rasterised at the GUI scale it's drawn at, not a fixed oversample (see ThemedText). */
@Mixin(TrueTypeGlyphProviderDefinition.class)
public abstract class TrueTypeGlyphProviderDefinitionMixin {
	@Shadow
	@Final
	private Identifier location;

	@ModifyExpressionValue(method = "load", at = @At(value = "FIELD",
		target = "Lnet/minecraft/client/gui/font/providers/TrueTypeGlyphProviderDefinition;oversample:F"))
	private float myriad$oversample(float oversample) {
		return location.getNamespace().equals("myriad") ? ThemedText.oversample() : oversample;
	}
}
