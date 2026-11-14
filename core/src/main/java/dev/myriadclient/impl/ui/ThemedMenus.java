package dev.myriadclient.impl.ui;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.ui.draw.FontFamily;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.impl.render.UiRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookSignScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Minecraft's other screens in the theme while Themed Screens is on: its menus and settings, the disconnect screen and
 * other mods' screens, as far as they're drawn with Minecraft's widgets. Their sprites (buttons, sliders, checkboxes, text
 * boxes, scrollbars, tabs) and backgrounds (the menu's, a list's, the separators above and below one) are swapped for the
 * theme's look as they're drawn (GuiGraphicsExtractorMixin); everything else of theirs (text, icons, layout) is left as it
 * is, so a mod's own screen stays its own. Behind them is the title screen's background, motes rising and you about now
 * and then (MenuScene), in place of the panorama and over the world alike; only the pause menu shows the world, frosted
 * under the theme's window colour as a panel is (motes and you there too). Container screens are ThemedScreens', and Myriad's own screens draw in
 * the theme anyway.
 */
public final class ThemedMenus {
	/** Room around a message screen's column inside its window. */
	private static final int WINDOW_PAD = 12;
	/** How far into a list its items fade into the background at an edge with more beyond it (GUI pixels). */
	private static final int FADE = 12;
	/** When the theme's background was last drawn (System.nanoTime): lists on it fade into it (listEdges). */
	private static long backgroundAt;
	/** Where the screen's list was drawn last frame (GUI pixels; -1 for none): above and below it, the bars (background). */
	private static float listTop = -1, listBottom;
	/** Where frosted widgets are drawn this frame, and were last frame (x, y, w, h on screen): motes behind them blur. */
	private static List<float[]> covered = new ArrayList<>(), coveredBefore = new ArrayList<>();
	/** Painting a themed sprite: what that draws is never themed again. */
	private static boolean painting;

	private ThemedMenus() {
	}

	private static ThemeSettings theme() {
		return Myriad.ui().theme();
	}

	/** How deep in a themed menu's own laying out and drawing the game is: its text is the theme's font meanwhile. */
	private static int textDepth;

	/** Whether Minecraft's default font is the theme's now (FontMixin): in a themed menu's laying out and drawing, with Theme Font on. */
	public static boolean themedText() {
		return textDepth > 0;
	}

	/**
	 * Runs {@code work} ({@code screen} laying itself out or drawing) with its text in the theme's font, where it's a
	 * themed menu and Theme Font is on. Not chat, books or signs: what's written there is laid out for everyone, in
	 * Minecraft's font.
	 */
	public static void withText(Screen screen, Runnable work) {
		boolean themed = Myriad.isReady() && theme().themedScreens.get() && theme().themeFont.get() && !(screen instanceof AbstractContainerScreen<?>)
			&& !screen.getClass().getName().startsWith("dev.myriadclient.impl.") && !(screen instanceof ChatScreen) && !(screen instanceof BookEditScreen)
			&& !(screen instanceof BookViewScreen) && !(screen instanceof BookSignScreen) && !(screen instanceof AbstractSignEditScreen);
		if (themed) textDepth++;
		try {
			work.run();
		} finally {
			if (themed) textDepth--;
		}
	}

	/** Whether the screen up is one drawn in the theme here (ThemedScreens draws the containers). */
	private static boolean on() {
		if (painting || !Myriad.isReady() || !theme().themedScreens.get()) return false;
		Screen screen = Minecraft.getInstance().gui.screen();
		return screen != null && !(screen instanceof AbstractContainerScreen<?>) && !screen.getClass().getName().startsWith("dev.myriadclient.impl.");
	}

	/**
	 * A widget's sprite at {@code x, y} ({@code w} by {@code h}, faded by {@code argb}'s alpha), drawn in the theme in its
	 * place; false to let Minecraft draw it (not a widget's, or not a themed screen).
	 */
	public static boolean sprite(GuiGraphicsExtractor ctx, Identifier sprite, int x, int y, int w, int h, int argb) {
		if (!sprite.getNamespace().equals(Identifier.DEFAULT_NAMESPACE) || !sprite.getPath().startsWith("widget/") || !on()) return false;
		String name = sprite.getPath().substring("widget/".length());
		boolean hover = name.contains("highlighted"), off = name.endsWith("disabled");
		Consumer<UiRenderer> draw;
		if (name.startsWith("button")) draw = c -> button(c, x, y, w, h, hover, off);
		else if (name.startsWith("slider_handle")) draw = c -> handle(c, x, y, w, h, hover);
		else if (name.startsWith("slider")) draw = c -> field(c, x, y, w, h, hover);
		else if (name.startsWith("checkbox")) draw = c -> checkbox(c, x, y, w, h, name.contains("selected"), hover);
		else if (name.startsWith("text_field")) draw = c -> field(c, x, y, w, h, hover);
		else if (name.equals("scroller_background")) draw = c -> track(c, x, y, w, h);
		else if (name.startsWith("scroller")) draw = c -> thumb(c, x, y, w, h);
		else if (name.startsWith("tab")) draw = c -> tab(c, x, y, w, h, name.contains("selected"), hover);
		else return false;
		if (name.startsWith("button") || name.startsWith("slider") && !name.startsWith("slider_handle") || name.startsWith("text_field")) {
			var pose = ctx.pose();
			float s = pose.m00();
			covered.add(new float[]{pose.m20() + x * s, pose.m21() + y * s, w * s, h * s});
		}
		if (!paint(ctx, argb, draw)) return false;
		// What the widget draws next (its label, a slider's handle) in a layer above: Minecraft sorts a layer's draws by
		// pipeline, which would put the theme's over its text.
		ctx.nextStratum();
		return true;
	}

	/** Whether a frosted widget (a button, a slider, a text box) was over {@code x, y} on the screen last frame: a mote there is blurred (MenuScene). */
	static boolean covered(float x, float y) {
		for (float[] r : coveredBefore) if (x >= r[0] && y >= r[1] && x < r[0] + r[2] && y < r[1] + r[3]) return true;
		return false;
	}

	/** A new frame's background: the widgets drawn on the last are what the motes go behind now. */
	private static void newFrame() {
		List<float[]> last = coveredBefore;
		coveredBefore = covered;
		covered = last;
		covered.clear();
	}

	/**
	 * A list's top and bottom edges ({@code y} and {@code bottom}), where Minecraft draws its separators: on the theme's
	 * background, its items fade into it there, as far as there's more of it scrolled out of sight that way, so the list
	 * ends without a line and nothing's cut off hard against the buttons below it. False elsewhere (Minecraft's lines).
	 */
	public static boolean listEdges(GuiGraphicsExtractor ctx, int x, int y, int w, int bottom, double scroll, int maxScroll) {
		if (!on()) return false;
		Screen screen = Minecraft.getInstance().gui.screen();
		// Over another background (a mod's own), no fade to match it: just no lines, as the separators' textures have.
		if (screen == null || System.nanoTime() - backgroundAt > 100_000_000L) return true;
		float above = (float) Math.min(1, scroll / FADE), below = (float) Math.min(1, (maxScroll - scroll) / FADE);
		listTop = y;
		listBottom = bottom;
		paint(ctx, -1, c -> {
			if (above > 0) fade(c, x, y, w, FADE, screen.height, above, true);
			if (below > 0) fade(c, x, bottom - FADE, w, FADE + 2, screen.height, below, false);
		});
		return true;
	}

	/** The background's colour from {@code y} to {@code y + h}, solid ({@code k} of it) at the list's edge and gone {@code h} into it. */
	private static void fade(UiRenderer c, float x, float y, float w, float h, float screenH, float k, boolean fromTop) {
		int top = backgroundColor(y, screenH), bottom = backgroundColor(y + h, screenH);
		int edge = (int) (k * 255);
		c.gradientRect(x, y, w, h, 0, ColorUtil.withAlpha(top, fromTop ? edge : 0), ColorUtil.withAlpha(bottom, fromTop ? 0 : edge), 90);
	}

	/** The theme's background (background) at {@code y}: the windows' colour at the top fading to the bar's at the bottom. */
	private static int backgroundColor(float y, float screenH) {
		ThemeSettings t = theme();
		return ColorUtil.lerp(ColorUtil.withAlpha(t.windowBackground.argb(), 0xFF), ColorUtil.withAlpha(t.barBackground.argb(), 0xFF), Math.clamp(y / screenH, 0, 1));
	}

	/**
	 * A screen's background texture at {@code x, y} ({@code w} by {@code h}): the menu's, a list's, or the separators above
	 * and below a list, drawn in the theme in its place (or left out); false for any other.
	 */
	public static boolean texture(GuiGraphicsExtractor ctx, Identifier texture, int x, int y, int w, int h, int argb) {
		if (!texture.getNamespace().equals(Identifier.DEFAULT_NAMESPACE) || !texture.getPath().startsWith("textures/gui/") || !on()) return false;
		Screen screen = Minecraft.getInstance().gui.screen();
		boolean whole = screen != null && x <= 0 && y <= 0 && x + w >= screen.width && y + h >= screen.height;
		Consumer<UiRenderer> draw = switch (texture.getPath().substring("textures/gui/".length())) {
			// Over part of the screen, the windows' colour. Over the whole of it, out of a world, nothing: the theme's background
			// is there already (panorama).
			case "menu_background.png" -> whole ? c -> {} : c -> c.rect(x, y, w, h, ColorUtil.withAlpha(theme().windowBackground.argb(), 0xC8));
			// In a world, the pause menu over the game as a panel is over it: the theme's frost (its Blur, in place of
			// Minecraft's) under the Window Background. Any other menu (options, a mod's settings) on the theme's own
			// background, as out of a world.
			case "inworld_menu_background.png" -> !whole ? c -> c.rect(x, y, w, h, ColorUtil.withAlpha(theme().windowBackground.argb(), 0xC8))
				: screen instanceof PauseScreen ? c -> {
					c.backdrop(x, y, w, h, 0, 1);
					c.rect(x, y, w, h, theme().windowBackground.argb());
					// Motes and you here too, for fun; not what its buttons blur, the world is.
					newFrame();
					MenuScene.draw(c, w, h, theme().accent.argb(), true, false, null);
				} : c -> background(c, w, h);
			// A list and the lines above and below it, nothing: one background from top to bottom, as the title screen's is
			// (its edges fade into it instead, listEdges).
			case "menu_list_background.png", "inworld_menu_list_background.png", "header_separator.png", "inworld_header_separator.png",
				 "footer_separator.png", "inworld_footer_separator.png" -> c -> {};
			case "tab_header_background.png" -> c -> c.rect(x, y, w, h, ColorUtil.withAlpha(theme().windowBackground.argb(), 0xA0));
			default -> null;
		};
		return draw != null && paint(ctx, argb, draw);
	}

	/**
	 * Out of a world, the theme's background behind {@code screen} in place of Minecraft's panorama; false to draw the
	 * panorama. Not on Minecraft's own title screen, which is the panorama's.
	 */
	public static boolean panorama(GuiGraphicsExtractor ctx, Screen screen) {
		return !(screen instanceof TitleScreen) && on() && paint(ctx, -1, c -> background(c, screen.width, screen.height));
	}

	/** The theme's own background, the title screen's (MyriadTitleScreen): the windows' colour fading to the bar's, and you peeking in now and then. */
	private static void background(UiRenderer c, float w, float h) {
		ThemeSettings t = theme();
		backgroundAt = System.nanoTime();
		newFrame();
		c.gradientRect(0, 0, w, h, 0, ColorUtil.withAlpha(t.windowBackground.argb(), 0xFF), ColorUtil.withAlpha(t.barBackground.argb(), 0xFF), 90);
		// A screen with a list: its title above and its buttons below on bars of the background itself, solid (no motes; you
		// over them), that the list's edges fade into (listEdges). Where the list was last frame.
		float top = listTop, bottom = listBottom;
		listTop = -1;
		MenuScene.draw(c, w, h, t.accent.argb(), true, true, top < 0 ? null : () -> {
			c.gradientRect(0, 0, w, top, 0, backgroundColor(0, h), backgroundColor(top, h), 90);
			c.gradientRect(0, bottom, w, h - bottom, 0, backgroundColor(bottom, h), backgroundColor(h, h), 90);
		});
	}

	/** Whether to leave out Minecraft's blur behind a menu: themed, there's the theme's own (texture) or nothing to blur (panorama). */
	public static boolean noBlur() {
		return on();
	}

	/** A window in the theme around {@code area} (a message screen's column), with room around it, behind what's in it. */
	public static void window(GuiGraphicsExtractor ctx, ScreenRectangle area) {
		if (!on() || area.width() <= 0) return;
		paint(ctx, -1, c -> ThemedScreens.window(c, theme(), area.left() - WINDOW_PAD, area.top() - WINDOW_PAD, area.width() + 2 * WINDOW_PAD,
			area.height() + 2 * WINDOW_PAD, true));
	}

	private static boolean paint(GuiGraphicsExtractor ctx, int argb, Consumer<UiRenderer> draw) {
		painting = true;
		try {
			float alpha = ColorUtil.alpha(argb) / 255f;
			// Cut off where Minecraft's own drawing is (a list's rows at its edges): its scissor is on screen, the drawing
			// moved and scaled to where the widget is (posed).
			ScreenRectangle scissor = ctx.scissorStack.peek();
			var pose = ctx.pose();
			float dx = pose.m20(), dy = pose.m21(), scale = pose.m00();
			return ThemedScreens.posed(ctx, c -> {
				c.push();
				if (scissor != null) c.clip((scissor.left() - dx) / scale, (scissor.top() - dy) / scale, scissor.width() / scale, scissor.height() / scale);
				if (alpha < 1) c.alpha(alpha);
				draw.accept(c);
				c.pop();
			});
		} finally {
			painting = false;
		}
	}

	private static float rounding(float size) {
		return Math.min(theme().rounding.get(), Math.min(4, size / 2));
	}

	/**
	 * A button, as the theme's: its surface, lighter and edged in the accent under the mouse, faint when it can't be
	 * pressed. Frosted over the background's you (MenuScene) as the title screen's buttons are over its scene.
	 */
	private static void button(UiRenderer c, float x, float y, float w, float h, boolean hover, boolean off) {
		ThemeSettings t = theme();
		// A pixel in from the top and bottom: Minecraft stacks some buttons with no gap (a list of key binds), and the
		// theme's are flat boxes that would run together where its own bevelled ones don't.
		y += 1;
		h -= 2;
		float r = rounding(h);
		if (MenuScene.frosted()) {
			c.setBlur(t.blur.get(), t.blurPasses.get(), t.blurSize.get().floatValue());
			c.backdrop(x, y, w, h, r, 1);
		}
		c.roundRect(x, y, w, h, r, off ? ColorUtil.withAlpha(t.surface.argb(), 0x50) : hover ? t.surfaceHover.argb() : t.surface.argb());
		c.outline(x, y, w, h, r, 1, hover ? t.accent.argb() : ColorUtil.withAlpha(t.inactiveBorder.argb(), 0x90));
	}

	/** A slider's track or a text box: darker than a button, edged in the accent while focused or under the mouse. */
	private static void field(UiRenderer c, float x, float y, float w, float h, boolean hover) {
		ThemeSettings t = theme();
		float r = rounding(h);
		c.roundRect(x, y, w, h, r, ColorUtil.withAlpha(t.windowBackground.argb(), 0xE0));
		c.outline(x, y, w, h, r, 1, hover ? t.accent.argb() : ColorUtil.withAlpha(t.inactiveBorder.argb(), 0x90));
	}

	/** A slider's handle, in the accent. */
	private static void handle(UiRenderer c, float x, float y, float w, float h, boolean hover) {
		ThemeSettings t = theme();
		c.roundRect(x + 1, y + 1, w - 2, h - 2, rounding(w), hover ? t.accent.argb() : ColorUtil.withAlpha(t.accent.argb(), 0xC0));
	}

	/** A checkbox: a field, filled in the accent with a tick when it's ticked. */
	private static void checkbox(UiRenderer c, float x, float y, float w, float h, boolean ticked, boolean hover) {
		ThemeSettings t = theme();
		float r = rounding(h);
		if (ticked) {
			c.roundRect(x, y, w, h, r, t.accent.argb());
			float size = c.defaultFontSize();
			c.text(FontFamily.MONO, size, Icons.CHECK, x + (w - c.textWidth(FontFamily.MONO, size, Icons.CHECK)) / 2, y + (h - c.textHeight(FontFamily.MONO, size)) / 2,
				t.windowBackground.argb());
			if (hover) c.outline(x, y, w, h, r, 1, t.text.argb());
		} else {
			field(c, x, y, w, h, hover);
		}
	}

	/** A scrollbar's groove: a thin line of the surface. */
	private static void track(UiRenderer c, float x, float y, float w, float h) {
		ThemeSettings t = theme();
		float tw = Math.min(w, 3);
		c.roundRect(x + (w - tw) / 2, y, tw, h, tw / 2, ColorUtil.withAlpha(t.surface.argb(), 0x90));
	}

	/** A scrollbar's thumb, in the accent. */
	private static void thumb(UiRenderer c, float x, float y, float w, float h) {
		ThemeSettings t = theme();
		float tw = Math.min(w, 4);
		c.roundRect(x + (w - tw) / 2, y, tw, h, tw / 2, t.accent.argb());
	}

	/** A tab above a page (settings, a new world): rounded on top, the one showing as the window it opens onto, edged in the accent. */
	private static void tab(UiRenderer c, float x, float y, float w, float h, boolean selected, boolean hover) {
		ThemeSettings t = theme();
		float r = rounding(h);
		c.roundRect(x, y, w, h, r, r, 0, 0, selected ? ColorUtil.withAlpha(t.windowBackground.argb(), 0xF0) : hover ? t.surfaceHover.argb() : t.surface.argb());
		if (selected) c.rect(x + r, y + h - 2, w - 2 * r, 2, t.accent.argb());
		else if (hover) c.rect(x + r, y + h - 1, w - 2 * r, 1, ColorUtil.withAlpha(t.accent.argb(), 0x90));
	}
}
