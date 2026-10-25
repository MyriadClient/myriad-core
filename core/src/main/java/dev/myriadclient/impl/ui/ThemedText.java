package dev.myriadclient.impl.ui;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.impl.MyriadImpl;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/**
 * Chat and item counts in the theme's font while Theme → Preferences → Theme Font is on. The font is registered with
 * Minecraft as {@code myriad:ui} (assets/myriad/font/ui.json: the theme's sans, then Minecraft's own for anything it
 * lacks), and the text is given it as its style's font, so Minecraft measures, wraps and finds clicks in it with the
 * widths it's drawn at.
 */
public final class ThemedText {
	public static final FontDescription FONT = new FontDescription.Resource(Identifier.fromNamespaceAndPath("myriad", "ui"));

	private ThemedText() {
	}

	public static boolean on() {
		return Myriad.isReady() && MyriadImpl.get().windowManager().theme().themeFont.get();
	}

	/** {@code text} in the theme's font (what in it names a font of its own keeps that one). */
	public static Component styled(Component text) {
		return Component.empty().append(text).withStyle(s -> s.withFont(FONT));
	}
}
