package dev.myriadclient.impl.mixin;

import dev.myriadclient.impl.ui.ThemedMenus;
import dev.myriadclient.impl.ui.ThemedText;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FontDescription;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Themed Screens with Theme Font: Minecraft's default font is the theme's while a themed menu lays itself out and draws
 * (see ThemedMenus.themedText), for measuring and drawing alike, so text fits where it's drawn.
 */
@Mixin(Font.class)
public abstract class FontMixin {
	@ModifyVariable(method = "getGlyphSource", at = @At("HEAD"), argsOnly = true)
	private FontDescription myriad$themeFont(FontDescription font) {
		return ThemedMenus.themedText() && FontDescription.DEFAULT.equals(font) ? ThemedText.MENU_FONT : font;
	}
}
