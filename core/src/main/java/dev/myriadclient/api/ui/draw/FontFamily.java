package dev.myriadclient.api.ui.draw;

/**
 * A font family known to the UI renderer. Myriad bundles Noto Sans (regular and semibold) and JetBrains Mono Nerd
 * Font, whose private-use glyphs give you thousands of icons (see nerdfonts.com/cheat-sheet). Glyphs missing from a
 * family fall back to the icon font, then to the JVM's default font. Only the constants here are known to the API; a
 * family with another id draws as {@link #SANS}.
 */
public record FontFamily(String id) {
	/** Noto Sans, regular: body text and the theme's default font. */
	public static final FontFamily SANS = new FontFamily("sans");
	/** Noto Sans, semibold: titles and headings. */
	public static final FontFamily SANS_BOLD = new FontFamily("sans_bold");
	/** JetBrains Mono Nerd Font: numbers that shouldn't jiggle, and icon glyphs ({@link dev.myriadclient.api.ui.Icons}). */
	public static final FontFamily MONO = new FontFamily("mono");
}
