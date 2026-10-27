package dev.myriadclient.api.render;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

/**
 * The theme's font as one of Minecraft's own, for text Minecraft draws: give a style {@link #FONT} as its font and
 * Minecraft draws, measures and wraps that text in it (chat, tooltips, item counts), rasterised at the GUI scale it's
 * drawn at, as Myriad's own text is. Anything it lacks comes from Minecraft's own font. Lines split in it are split
 * again (chat's {@code rescaleChat}) when the GUI scale changes, since the widths change a little with it.
 *
 * <pre>{@code
 * graphics.text(mc.font, ThemeFont.styled(Component.literal("64")), x, y, color, true);
 * }</pre>
 */
@ApiStatus.AvailableSince("1.3")
public final class ThemeFont {
	public static final FontDescription FONT = new FontDescription.Resource(Identifier.fromNamespaceAndPath("myriad", "ui"));

	private ThemeFont() {
	}

	/** {@code text} in the theme's font (what in it names a font of its own keeps that one). */
	public static Component styled(Component text) {
		return Component.empty().append(text).withStyle(s -> s.withFont(FONT));
	}
}
