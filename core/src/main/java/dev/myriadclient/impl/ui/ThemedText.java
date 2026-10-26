package dev.myriadclient.impl.ui;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.impl.MyriadImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/**
 * Item counts in the theme's font while Theme → Preferences → Theme Font is on, and chat while Chat → Font is Theme
 * (ThemedChat). The font is registered with
 * Minecraft as {@code myriad:ui} (assets/myriad/font/ui.json: the theme's sans, then Minecraft's own for anything it
 * lacks), and the text is given it as its style's font, so Minecraft measures, wraps and finds clicks in it with the
 * widths it's drawn at.
 * <p>
 * Minecraft rasterises a TTF once, at its oversample, and scales that: thin strokes thin out further unless it's the
 * GUI scale the glyphs are drawn at. So the font is rasterised at the GUI scale (TrueTypeGlyphProviderDefinitionMixin),
 * and when that has changed and settled, with the font on, Minecraft's resources are reloaded (as F3+T does) to
 * rasterise it again.
 */
public final class ThemedText {
	public static final FontDescription FONT = new FontDescription.Resource(Identifier.fromNamespaceAndPath("myriad", "ui"));
	public static final ThemedText INSTANCE = new ThemedText();
	/** Ticks the GUI scale has to stay changed before the font is rasterised again (a window being resized steps through several). */
	private static final int SETTLE_TICKS = 20;

	/** The GUI scale the font was last rasterised at (0 before it was). */
	private static volatile int rasterised;
	private int settled;

	private ThemedText() {
	}

	/** The oversample to rasterise the theme's font at: the GUI scale now, noted as the one it's rasterised at. */
	public static float oversample() {
		var window = Minecraft.getInstance().getWindow();
		rasterised = window == null ? 1 : Math.max(1, window.getGuiScale());
		return rasterised;
	}

	@Subscribe
	private void onTick(TickEvent.Post e) {
		var mc = Minecraft.getInstance();
		if (rasterised == 0 || mc.getWindow() == null || !on() && !ThemedChat.themeFont() || Math.max(1, mc.getWindow().getGuiScale()) == rasterised) {
			settled = 0;
			return;
		}
		if (++settled < SETTLE_TICKS) return;
		settled = 0;
		rasterised = 0;
		mc.reloadResourcePacks();
	}

	public static boolean on() {
		return Myriad.isReady() && MyriadImpl.get().windowManager().theme().themeFont.get();
	}

	/** {@code text} in the theme's font (what in it names a font of its own keeps that one). */
	public static Component styled(Component text) {
		return Component.empty().append(text).withStyle(s -> s.withFont(FONT));
	}
}
