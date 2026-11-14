package dev.myriadclient.api.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.setting.Settings;
import dev.myriadclient.api.ui.draw.Bezier;
import dev.myriadclient.api.ui.draw.FontFamily;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.ApiStatus;

/**
 * The live look of the menu, modelled on Hyprland's {@code general}, {@code decoration} and {@code animations}
 * sections, from {@link Ui#theme()}. {@link #settings} is the theme itself: every value a theme file holds, written
 * whenever a theme is applied and saved back into the active theme when edited. {@link #preferences} are personal
 * options that stay the same whichever theme is active (UI scale, input).
 * <p>
 * Read it while drawing, every frame, rather than caching values, so your panels follow theme changes: colours with
 * {@code theme.accent.argb()}, sizes with {@code theme.rounding.get()}. Sizes are in UI units unless a field says
 * otherwise. A {@link Theme}'s apply function sets these fields.
 */
public final class ThemeSettings {
	/** The theme's text font. */
	public enum Font {
		/** Noto Sans ({@link FontFamily#SANS}). */
		SANS,
		/** JetBrains Mono ({@link FontFamily#MONO}). */
		MONO
	}

	/** How switching workspaces animates. */
	public enum WorkspaceAnimation {
		/** The workspaces slide sideways, the higher number coming from the right. */
		SLIDE,
		/** The workspaces slide vertically, the higher number coming from below. */
		SLIDE_VERTICAL,
		/** The old workspace fades out as the new one fades in. */
		FADE,
		/** The new workspace shows at once. */
		NONE
	}

	/**
	 * The modifier that, held, turns keys and clicks into menu shortcuts ({@link #modKey}): workspaces, launcher,
	 * moving and resizing windows. Both the left and the right key count.
	 */
	public enum ModKey {
		/** Alt, the default: Hyprland keeps Super for itself. */
		ALT(InputConstants.KEY_LALT, InputConstants.KEY_RALT, InputConstants.MOD_ALT),
		/** Super (the Windows or Command key). */
		SUPER(InputConstants.KEY_LGUI, InputConstants.KEY_RGUI, InputConstants.MOD_SUPER),
		/** Control. */
		CTRL(InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL, InputConstants.MOD_CONTROL);

		private final int left, right, mask;
		/** Held, as the window's key events last said (left and right). */
		private volatile boolean leftHeld, rightHeld;

		ModKey(int left, int right, int mask) {
			this.left = left;
			this.right = right;
			this.mask = mask;
		}

		/** Whether it was held with an input whose modifier bits are {@code modifiers} (a click's, say). */
		@ApiStatus.AvailableSince("1.2")
		public boolean in(int modifiers) {
			return (modifiers & mask) != 0;
		}

		/**
		 * Whether it's held now, on either side: as the window's key events last said, or its key state (some compositors
		 * keep the window from seeing a held modifier in the key state).
		 */
		@ApiStatus.AvailableSince("1.2")
		public boolean isDown() {
			Minecraft mc = Minecraft.getInstance();
			// Its release goes to another window once focus has moved (Alt-Tab, say).
			if (!mc.isWindowActive()) leftHeld = rightHeld = false;
			return leftHeld || rightHeld || InputConstants.isKeyDown(left) || InputConstants.isKeyDown(right);
		}

		/** Core's: a key event from the window (KeyboardHandlerMixin), pressed or released, if it's one of the modifier keys. */
		@ApiStatus.Internal
		public static void keyEvent(int key, int action) {
			for (ModKey m : values()) {
				if (key == m.left) m.leftHeld = action != InputConstants.RELEASE;
				if (key == m.right) m.rightHeld = action != InputConstants.RELEASE;
			}
		}
	}

	/** Which screen edge the bar is on. */
	public enum BarPosition {
		/** Along the top edge. */
		TOP,
		/** Along the bottom edge. */
		BOTTOM
	}

	/** The easing curves the theme's animations can use, each a {@link Bezier} preset. */
	public enum Curve {
		/** {@link Bezier#EASE_OUT_QUINT}: fast start, long gentle stop. */
		EASE_OUT_QUINT(Bezier.EASE_OUT_QUINT),
		/** {@link Bezier#EASE_OUT_EXPO}: faster start, sharper stop. */
		EASE_OUT_EXPO(Bezier.EASE_OUT_EXPO),
		/** {@link Bezier#EASE_IN_OUT_CUBIC}: slow at both ends. */
		EASE_IN_OUT_CUBIC(Bezier.EASE_IN_OUT_CUBIC),
		/** {@link Bezier#OVERSHOT}: goes a little past the end and settles back. */
		OVERSHOT(Bezier.OVERSHOT),
		/** {@link Bezier#SMOOTH}: like CSS {@code ease}. */
		SMOOTH(Bezier.SMOOTH),
		/** {@link Bezier#LINEAR}: constant speed. */
		LINEAR(Bezier.LINEAR);

		/** The curve itself. */
		public final Bezier bezier;

		Curve(Bezier bezier) {
			this.bezier = bezier;
		}
	}

	/** Everything a theme defines. */
	public final Settings settings = new Settings();
	/** Personal options that don't change with the theme. */
	public final Settings preferences = new Settings();

	private final SettingGroup spGeneral = preferences.group("General");
	/**
	 * Multiplier on the automatic UI scale (a UI unit is {@code max(1, framebuffer height / 540)} pixels times this).
	 * Changed in the open menu, it takes effect once the slider is let go and asks to be kept, as a screen resolution
	 * does.
	 */
	public final DoubleSetting uiScale = spGeneral.decimal("UI Scale").description("Multiplier on the automatic UI scale.").defaultValue(1.0).range(0.5, 3).decimals(2).build();
	/** Pause singleplayer while the menu is open. */
	public final BoolSetting pauseGame = spGeneral.bool("Pause Game").description("Pause singleplayer while the menu is open.").defaultValue(false).build();

	private final SettingGroup sgGeneral = settings.group("General");
	/** The UI's text font; see {@link #fontFamily()}. */
	public final EnumSetting<Font> font = sgGeneral.oneOf("Font", Font.SANS).build();
	/** Size of the UI's text, in UI units: {@link dev.myriadclient.api.ui.draw.Canvas#defaultFontSize()}. */
	public final DoubleSetting fontSize = sgGeneral.decimal("Font Size").defaultValue(7.5).range(5, 14).decimals(1).build();
	/** Gap between tiled windows, in UI units. */
	public final IntSetting gapsIn = sgGeneral.integer("Gaps In").description("Gap between tiled windows.").defaultValue(3).range(0, 40).build();
	/** Gap between the tiled windows and the screen edge (and the bar), in UI units. */
	public final IntSetting gapsOut = sgGeneral.integer("Gaps Out").description("Gap between windows and the screen edge.").defaultValue(6).range(0, 80).build();
	/** Width of window borders, in UI units (at least one pixel when not 0); 0 draws none. */
	public final IntSetting borderSize = sgGeneral.integer("Border Size").defaultValue(1).range(0, 6).build();
	/** Whether windows have a title bar (title, icon, controls; drag it to move floating windows). */
	public final BoolSetting titleBars = sgGeneral.bool("Title Bars").description("Show a title bar on windows (drag it to move floating windows).").defaultValue(true).build();

	// The defaults are Myriad's own look, the logo's: its mint on its near-black, with neutral greys between.
	private final SettingGroup sgColors = settings.group("Colors");
	/** The accent: the focused window's icon, switches that are on, selections, sliders, focused fields. */
	public final ColorSetting accent = sgColors.color("Accent").defaultValue(0xFF7FD8AE).build();
	/** Second accent, used for gradients (the active border's second stop by default). */
	public final ColorSetting secondary = sgColors.color("Secondary").description("Second accent, used for gradients.").defaultValue(0xFF4FA884).build();
	/** The focused window's border, its gradient's first stop; the accent by default. */
	public final ColorSetting activeBorderFrom = sgColors.color("Active Border").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT)).build();
	/** The focused window's border, its gradient's second stop; the secondary colour by default. */
	public final ColorSetting activeBorderTo = sgColors.color("Active Border End").description("Second gradient stop.").defaultValue(SettingColor.role(SettingColor.Mode.SECONDARY)).build();
	/** Direction of the active border's gradient, in degrees (0 left to right, 90 top to bottom). */
	public final DoubleSetting borderAngle = sgColors.decimal("Border Angle").defaultValue(45).range(0, 360).decimals(0).build();
	/** Rotate the active border's gradient over time ({@link #borderSpeed}), while animations are on. */
	public final BoolSetting animateBorder = sgColors.bool("Animate Border").description("Rotate the active border gradient.").defaultValue(false).build();
	/** The border of windows that aren't focused. */
	public final ColorSetting inactiveBorder = sgColors.color("Inactive Border").defaultValue(0x60303633).build();
	/** The fill behind every window's content (over the frosted glass when {@link #blur} is on). */
	public final ColorSetting windowBackground = sgColors.color("Window Background").defaultValue(0xD8131514).build();
	/** The fill of window title bars, over the window background. */
	public final ColorSetting titleBackground = sgColors.color("Title Background").defaultValue(0x400C0C0C).build();
	/** Buttons, inputs and rows: the resting fill of interactive widgets. */
	public final ColorSetting surface = sgColors.color("Surface").description("Buttons, inputs and rows.").defaultValue(0x60252A27).build();
	/** {@link #surface} under the pointer or focused. */
	public final ColorSetting surfaceHover = sgColors.color("Surface Hover").defaultValue(0x90343A37).build();
	/** Normal text. */
	public final ColorSetting text = sgColors.color("Text").defaultValue(0xFFE9EEEB).build();
	/** Secondary text: hints, labels, placeholders, unfocused titles. */
	public final ColorSetting textDim = sgColors.color("Text Dim").defaultValue(0xFF8C9690).build();
	/** Drawn over the game behind the menu (not while editing the HUD). */
	public final ColorSetting menuTint = sgColors.color("Menu Tint").description("Drawn over the game behind the menu.").defaultValue(0x700C0C0C).build();
	/** The bar's fill. */
	public final ColorSetting barBackground = sgColors.color("Bar Background").defaultValue(0xE00C0C0C).build();

	private final SettingGroup sgPalette = settings.group("Palette");
	/** Danger, hostile mobs, the Combat category; also a {@link SettingColor} role. */
	public final ColorSetting red = sgPalette.color("Red").description("Danger, hostile mobs, the Combat category.").defaultValue(0xFFF38BA8).build();
	/** Success, passive mobs, the Player category; also a {@link SettingColor} role. */
	public final ColorSetting green = sgPalette.color("Green").description("Success, passive mobs, the Player category.").defaultValue(0xFFA6E3A1).build();
	/** Warnings, items, the World category; also a {@link SettingColor} role. */
	public final ColorSetting yellow = sgPalette.color("Yellow").description("Warnings, items, the World category.").defaultValue(0xFFF9E2AF).build();
	/** The palette's blue; also a {@link SettingColor} role. */
	public final ColorSetting blue = sgPalette.color("Blue").defaultValue(0xFF89B4FA).build();
	/** The palette's magenta; also a {@link SettingColor} role. */
	public final ColorSetting magenta = sgPalette.color("Magenta").defaultValue(0xFFCBA6F7).build();
	/** Friends; also a {@link SettingColor} role. */
	public final ColorSetting cyan = sgPalette.color("Cyan").description("Friends.").defaultValue(0xFF94E2D5).build();

	private final SettingGroup sgDecoration = settings.group("Decoration");
	/** Corner radius of windows, in UI units. */
	public final IntSetting rounding = sgDecoration.integer("Rounding").defaultValue(4).range(0, 30).build();
	/** Frosted-glass window backgrounds: the game blurred behind each window ({@code Canvas.backdrop}). */
	public final BoolSetting blur = sgDecoration.bool("Blur").description("Frosted-glass window backgrounds.").defaultValue(true).build();
	/** How many blur passes run; more is softer and costs more. */
	public final IntSetting blurPasses = sgDecoration.integer("Blur Passes").defaultValue(3).range(1, 6).visible(blur::get).build();
	/** How far each blur pass samples; larger is blurrier. */
	public final DoubleSetting blurSize = sgDecoration.decimal("Blur Size").defaultValue(2.5).range(0.5, 8).decimals(1).visible(blur::get).build();
	/** A drop shadow under windows (not fullscreen ones). */
	public final BoolSetting shadow = sgDecoration.bool("Shadow").defaultValue(false).build();
	/** How far the window shadow spreads, in UI units. */
	public final IntSetting shadowRange = sgDecoration.integer("Shadow Range").defaultValue(14).range(1, 60).visible(shadow::get).build();
	/** The window shadow's colour. */
	public final ColorSetting shadowColor = sgDecoration.color("Shadow Color").defaultValue(0x99000000).visible(shadow::get).build();
	/** Darken unfocused windows: the opacity of black drawn over them, 0 for none. */
	public final DoubleSetting dimInactive = sgDecoration.decimal("Dim Inactive").description("Darken unfocused windows.").defaultValue(0.0).range(0, 0.8).decimals(2).build();
	/** Opacity of unfocused windows, 1 for fully opaque. */
	public final DoubleSetting inactiveOpacity = sgDecoration.decimal("Inactive Opacity").defaultValue(1.0).range(0.2, 1).decimals(2).build();

	private final SettingGroup sgAnimations = settings.group("Animations");
	/** Whether the UI animates; off, every duration is 0 ({@link #durationMs}). */
	public final BoolSetting animations = sgAnimations.bool("Enabled").defaultValue(true).build();
	/** The easing of window and workspace animations; see {@link #bezier()}. */
	public final EnumSetting<Curve> curve = sgAnimations.oneOf("Curve", Curve.EASE_OUT_QUINT).visible(animations::get).build();
	/** How long windows take to open, close and move, in tenths of a second, like Hyprland. */
	public final DoubleSetting windowSpeed = sgAnimations.decimal("Window Speed").description("Duration in tenths of a second, like Hyprland.").defaultValue(3.79).range(0.5, 20).decimals(2).visible(animations::get).build();
	/** How long switching workspaces takes, in tenths of a second. */
	public final DoubleSetting workspaceSpeed = sgAnimations.decimal("Workspace Speed").defaultValue(3.0).range(0.5, 20).decimals(1).visible(animations::get).build();
	/** How switching workspaces looks. */
	public final EnumSetting<WorkspaceAnimation> workspaceAnimation = sgAnimations.oneOf("Workspace Style", WorkspaceAnimation.SLIDE).visible(animations::get).build();
	/** The scale windows grow from when opening, as a fraction of their size (Hyprland's popin %). */
	public final DoubleSetting popin = sgAnimations.decimal("Popin").description("Scale windows grow from when opening (Hyprland's popin %).").defaultValue(0.87).range(0.5, 1).decimals(2).visible(animations::get).build();
	/** Seconds per full turn of the active border's gradient, with {@link #animateBorder}. */
	public final DoubleSetting borderSpeed = sgAnimations.decimal("Border Speed").description("Seconds per border gradient rotation.").defaultValue(6).range(1, 60).decimals(1).visible(() -> animations.get() && animateBorder.get()).build();

	private final SettingGroup sgBar = settings.group("Bar");
	/** Whether the menu shows the bar of {@link BarWidget}s. */
	public final BoolSetting bar = sgBar.bool("Show Bar").defaultValue(true).build();
	/** Which edge the bar is on. */
	public final EnumSetting<BarPosition> barPosition = sgBar.oneOf("Position", BarPosition.TOP).visible(bar::get).build();
	/** The bar's height, in UI units; windows tile in the rest of the screen. */
	public final IntSetting barHeight = sgBar.integer("Height").defaultValue(14).range(10, 40).visible(bar::get).build();

	private final SettingGroup spCosmetics = preferences.group("Cosmetics");
	/**
	 * Wear Myriad's cape and elytra, seen by you and by Myriad players near you; turning it on or off tells the server
	 * at once.
	 */
	public final BoolSetting myriadCape = spCosmetics.bool("Myriad Cape")
		.description("Wear Myriad's cape and elytra in place of your own, seen by you and by Myriad players near you. Off: they see your own cape again; you still see theirs.")
		.defaultValue(true).onChanged(v -> {
			// Tell the server at once, so players near you see the change.
			var mc = Minecraft.getInstance();
			if (mc.player != null) mc.options.broadcastOptions();
		}).build();
	/**
	 * Screens in the theme: Myriad's own title screen, your inventory and the storage screens, and Minecraft's other
	 * screens too (menus, settings, the disconnect screen, other mods' screens), as far as they're made of Minecraft's
	 * widgets (buttons, sliders, checkboxes, text boxes, lists, scrollbars, tabs) and backgrounds; their layouts stay
	 * their own.
	 */
	@ApiStatus.AvailableSince("1.2")
	public final BoolSetting themedScreens = spCosmetics.bool("Themed Screens")
		.description("Draw Minecraft's screens in the theme: Myriad's own title screen, your inventory, chests and the other storage screens, "
			+ "the menus and settings, and other mods' screens (their buttons, sliders, lists and backgrounds; their layouts stay theirs). "
			+ "Off: Minecraft's own look, with Myriad's logo and keys in a corner of its title screen.")
		.defaultValue(true).build();
	/**
	 * Item counts in the theme's font ({@link dev.myriadclient.api.ui.draw.ThemeFont}), and with {@link #themedScreens}
	 * the menus' text.
	 */
	@ApiStatus.AvailableSince("1.2")
	public final BoolSetting themeFont = spCosmetics.bool("Theme Font")
		.description("Item counts in the theme's font, and with Themed Screens the menus' text too (not chat, books or signs). Off: Minecraft's own.")
		.defaultValue(true).build();
	/** The hotbar and the off hand's slot in the theme (the status bars and experience bar have their own options). */
	@ApiStatus.AvailableSince("1.2")
	public final BoolSetting themedHotbar = spCosmetics.bool("Themed Hotbar")
		.description("Draw the hotbar and the off hand's slot in the theme. Off: Minecraft's own look.")
		.defaultValue(true).build();
	/** Hearts, food and armour over the hotbar in the theme. */
	@ApiStatus.AvailableSince("1.3")
	public final BoolSetting themedStatusBars = spCosmetics.bool("Themed Status Bars")
		.description("Draw hearts, food and armour in the theme. Off: Minecraft's own look.")
		.defaultValue(true).build();
	/** The experience bar and your level in the theme. */
	@ApiStatus.AvailableSince("1.3")
	public final BoolSetting themedExperienceBar = spCosmetics.bool("Themed Experience Bar")
		.description("Draw the experience bar and your level in the theme. Off: Minecraft's own look.")
		.defaultValue(true).build();

	private final SettingGroup sgInput = preferences.group("Input");
	/** The modifier for menu shortcuts; see {@link ModKey}. */
	public final EnumSetting<ModKey> modKey = sgInput.oneOf("Mod Key", ModKey.ALT)
		.description("Modifier for menu shortcuts (Omarchy's Super). Hyprland keeps Super for itself, so Alt is the default.").build();
	/**
	 * h/j/k/l, gg/G and Ctrl+d/u move the keyboard selection inside windows
	 * ({@link dev.myriadclient.api.ui.widget.WidgetRoot}); arrow keys always do.
	 */
	public final BoolSetting vimKeys = sgInput.bool("Vim Keys").description("h/j/k/l, gg/G and Ctrl+d/u move the selection inside windows (arrow keys always work).").defaultValue(true).build();

	private final SettingGroup sgHud = settings.group("HUD");
	/** Draw a translucent card (frosted, in {@link #windowBackground}) behind each HUD element. */
	public final BoolSetting hudBackground = sgHud.bool("HUD Backgrounds").description("Draw a translucent card behind HUD elements.").defaultValue(false).build();
	/** How far HUD elements stay from the screen edges, in UI units: their anchor points are inset by it. */
	public final IntSetting hudPadding = sgHud.integer("HUD Edge Padding").defaultValue(4).range(0, 40).build();

	/**
	 * The ARGB colour {@code role} stands for in this theme (never a role itself, so roles can't recurse). Modes that
	 * aren't palette roles ({@code STATIC}, {@code RAINBOW}) give the accent.
	 */
	public int roleColor(SettingColor.Mode role) {
		return switch (role) {
			case SECONDARY -> secondary.get().color();
			case RED -> red.get().color();
			case GREEN -> green.get().color();
			case YELLOW -> yellow.get().color();
			case BLUE -> blue.get().color();
			case MAGENTA -> magenta.get().color();
			case CYAN -> cyan.get().color();
			case TEXT -> text.get().color();
			default -> accent.get().color();
		};
	}

	/** The font family for {@link #font}: what the canvas draws text in by default. */
	public FontFamily fontFamily() {
		return font.get() == Font.MONO ? FontFamily.MONO : FontFamily.SANS;
	}

	/** Animation duration in ms for a Hyprland-style speed (tenths of a second), or 0 if animations are off. */
	public float durationMs(DoubleSetting speed) {
		return animations.get() ? (float) (speed.get() * 100) : 0;
	}

	/** The easing curve chosen in {@link #curve}, for {@link dev.myriadclient.api.ui.draw.Animated#animateTo}. */
	public Bezier bezier() {
		return curve.get().bezier;
	}
}
