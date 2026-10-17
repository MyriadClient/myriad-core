package dev.myriadclient.api.render;

/**
 * A font family known to the UI renderer. Myriad bundles Noto Sans (regular and semibold) and JetBrains Mono Nerd
 * Font, whose private-use glyphs give you thousands of icons (see nerdfonts.com/cheat-sheet). Glyphs missing from a
 * family fall back to the icon font, then to the JVM's default font.
 */
public record FontFamily(String id) {
	public static final FontFamily SANS = new FontFamily("sans");
	public static final FontFamily SANS_BOLD = new FontFamily("sans_bold");
	public static final FontFamily MONO = new FontFamily("mono");
}
