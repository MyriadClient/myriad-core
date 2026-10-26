package dev.myriadclient.impl.ui;

import com.mojang.blaze3d.font.GlyphProvider;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WindowResizeEvent;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.mixin.FontSetAccessor;
import dev.myriadclient.impl.render.font.ThemeGlyphs;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Item counts in the theme's font while Theme → Preferences → Theme Font is on, and chat while Chat → Font is Theme
 * (ThemedChat). The font is registered with Minecraft as {@code myriad:ui}: Myriad's own glyphs (ThemeGlyphs, given it
 * by FontManagerMixin), then what assets/myriad/font/ui.json lists (Minecraft's own, for anything they lack). The text
 * is given it as its style's font, so Minecraft measures, wraps and finds clicks in it with the widths it's drawn at.
 * <p>
 * The glyphs are rasterised at the GUI scale they're drawn at, as Myriad's own text is. When the scale changes, they're
 * given the new one and only this font is reloaded (FontSet.reload, as Force Unicode does), not the resources.
 */
public final class ThemedText {
	public static final Identifier FONT_ID = Identifier.fromNamespaceAndPath("myriad", "ui");
	public static final FontDescription FONT = new FontDescription.Resource(FONT_ID);
	public static final ThemedText INSTANCE = new ThemedText();

	private static @Nullable ThemeGlyphs glyphs;
	/** The font set Minecraft made for {@code myriad:ui} at its last resource reload. */
	private static @Nullable FontSet set;

	private ThemedText() {
	}

	/** {@code myriad:ui}'s providers, Myriad's glyphs first, as {@code set} is loaded with them. */
	public static List<GlyphProvider.Conditional> providers(FontSet set, List<GlyphProvider.Conditional> providers) {
		ThemedText.set = set;
		if (glyphs == null) glyphs = new ThemeGlyphs("ui-semibold.ttf", guiScale());
		else glyphs.scale(guiScale());
		List<GlyphProvider.Conditional> all = new ArrayList<>(providers.size() + 1);
		all.add(new GlyphProvider.Conditional(glyphs, FontOption.Filter.ALWAYS_PASS));
		all.addAll(providers);
		return all;
	}

	/**
	 * The glyphs at the new GUI scale: the font set's baked ones dropped, as a reload drops them, but its providers kept
	 * (a reload chooses them again, going through every glyph Minecraft's own fonts have) and its glyphs by advance.
	 */
	@Subscribe
	private void onResize(WindowResizeEvent e) {
		rescale();
	}

	/** A scale set before Myriad was up to hear of it (the window resized while the game loaded). */
	@Subscribe
	private void onTick(TickEvent.Post e) {
		rescale();
	}

	private static void rescale() {
		int scale = guiScale();
		if (glyphs == null || set == null || glyphs.scale() == scale) return;
		glyphs.scale(scale);
		var fonts = (FontSetAccessor) set;
		var widths = new Int2ObjectOpenHashMap<>(fonts.myriad$glyphsByWidth());
		fonts.myriad$resetTextures();
		fonts.myriad$glyphsByWidth().putAll(widths);
		var mc = Minecraft.getInstance();
		if (ThemedChat.themeFont() && mc.gui != null) mc.gui.hud.getChat().rescaleChat();
	}

	private static int guiScale() {
		var window = Minecraft.getInstance().getWindow();
		return window == null ? 1 : Math.max(1, window.getGuiScale());
	}

	public static boolean on() {
		return Myriad.isReady() && MyriadImpl.get().windowManager().theme().themeFont.get();
	}

	/** {@code text} in the theme's font (what in it names a font of its own keeps that one). */
	public static Component styled(Component text) {
		return Component.empty().append(text).withStyle(s -> s.withFont(FONT));
	}
}
