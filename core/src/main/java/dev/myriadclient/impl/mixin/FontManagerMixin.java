package dev.myriadclient.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.font.GlyphProvider;
import dev.myriadclient.impl.ui.ThemedText;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Set;

/** The theme's fonts ({@code myriad:ui}, {@code myriad:menu}) given Myriad's own glyphs first, before what their json lists (see ThemedText). */
@Mixin(FontManager.class)
public abstract class FontManagerMixin {
	@WrapOperation(method = "createFontSet", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/font/FontSet;reload(Ljava/util/List;Ljava/util/Set;)V"))
	private void myriad$themeGlyphs(FontSet set, List<GlyphProvider.Conditional> providers, Set<FontOption> options, Operation<Void> original,
									@Local(argsOnly = true) Identifier id) {
		original.call(set, ThemedText.providers(id, set, providers), options);
	}
}
