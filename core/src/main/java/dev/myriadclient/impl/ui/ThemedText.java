package dev.myriadclient.impl.ui;

import com.mojang.blaze3d.font.GlyphProvider;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WindowResizeEvent;
import dev.myriadclient.api.ui.draw.ThemeFont;
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
import java.util.function.DoubleSupplier;

/**
 * The theme's font as one of Minecraft's ({@link ThemeFont}), and item counts in it while Theme → Preferences → Theme
 * Font is on. It's registered with Minecraft as {@code myriad:ui}: Myriad's own glyphs (ThemeGlyphs, given it by
 * FontManagerMixin), then what assets/myriad/font/ui.json lists (Minecraft's own, for anything they lack). Text is
 * given it as its style's font, so Minecraft measures, wraps and finds clicks in it with the widths it's drawn at.
 * <p>
 * Themed menus' text (ThemedMenus.withText) is in {@code myriad:menu}, the title screen's font: the regular weight at
 * the theme's Font Size, so it sits in Minecraft's buttons as the title screen's text does in its own. Chat, tooltips and
 * counts keep Minecraft's size.
 * <p>
 * The glyphs are rasterised at the GUI scale they're drawn at, as Myriad's own text is. When the scale changes (or the
 * Font Size, for the menus'), they're given the new one and only that font is reloaded (FontSet.reload, as Force Unicode
 * does), not the resources.
 */
public final class ThemedText {
	public static final Identifier FONT_ID = ((FontDescription.Resource) ThemeFont.FONT).id();
	public static final FontDescription MENU_FONT = new FontDescription.Resource(Identifier.fromNamespaceAndPath("myriad", "menu"));
	public static final ThemedText INSTANCE = new ThemedText();

	/** One of the two: its font's id and file, the em (GUI pixels) and how far below Minecraft's baseline it sits (NaN: centred as Minecraft's text is). */
	private static final class Face {
		final Identifier id;
		final String file;
		final DoubleSupplier size;
		final float shift;
		@Nullable ThemeGlyphs glyphs;
		/** The font set Minecraft made for it at its last resource reload. */
		@Nullable FontSet set;

		Face(Identifier id, String file, DoubleSupplier size, float shift) {
			this.id = id;
			this.file = file;
			this.size = size;
			this.shift = shift;
		}
	}

	/** As ui.json had it for the ttf provider: semibold, as Minecraft's text is heavy, at its size, a pixel below its baseline. */
	private static final Face UI = new Face(FONT_ID, "ui-semibold.ttf", () -> 9.5, 1);
	/** As the title screen's text: regular, at the theme's Font Size. */
	private static final Face MENU = new Face(((FontDescription.Resource) MENU_FONT).id(), "ui-regular.ttf",
		() -> Myriad.isReady() ? MyriadImpl.get().windowManager().theme().fontSize.get() : 7.5, Float.NaN);

	private ThemedText() {
	}

	/** {@code id}'s providers, Myriad's glyphs first where it's one of the theme's fonts, as {@code set} is loaded with them. */
	public static List<GlyphProvider.Conditional> providers(Identifier id, FontSet set, List<GlyphProvider.Conditional> providers) {
		Face face = UI.id.equals(id) ? UI : MENU.id.equals(id) ? MENU : null;
		if (face == null) return providers;
		face.set = set;
		if (face.glyphs == null) face.glyphs = new ThemeGlyphs(face.file, guiScale(), (float) face.size.getAsDouble(), face.shift);
		else {
			face.glyphs.scale(guiScale());
			face.glyphs.size((float) face.size.getAsDouble());
		}
		List<GlyphProvider.Conditional> all = new ArrayList<>(providers.size() + 1);
		all.add(new GlyphProvider.Conditional(face.glyphs, FontOption.Filter.ALWAYS_PASS));
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

	/** A scale set before Myriad was up to hear of it (the window resized while the game loaded), or a new Font Size. */
	@Subscribe
	private void onTick(TickEvent.Post e) {
		rescale();
	}

	private static void rescale() {
		// Chat split in it (an addon's chat in the theme's font) is split again: the widths change a little with the scale.
		if (rescale(UI)) {
			var mc = Minecraft.getInstance();
			if (mc.gui != null) mc.gui.hud.getChat().rescaleChat();
		}
		rescale(MENU);
	}

	private static boolean rescale(Face face) {
		int scale = guiScale();
		float size = (float) face.size.getAsDouble();
		if (face.glyphs == null || face.set == null || face.glyphs.scale() == scale && face.glyphs.size() == size) return false;
		face.glyphs.scale(scale);
		face.glyphs.size(size);
		var fonts = (FontSetAccessor) face.set;
		var widths = new Int2ObjectOpenHashMap<>(fonts.myriad$glyphsByWidth());
		fonts.myriad$resetTextures();
		fonts.myriad$glyphsByWidth().putAll(widths);
		return true;
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
		return ThemeFont.styled(text);
	}
}
