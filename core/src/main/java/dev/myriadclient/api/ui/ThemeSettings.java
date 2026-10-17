package dev.myriadclient.api.ui;

import dev.myriadclient.api.render.Bezier;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.setting.Settings;

/**
 * The live look of the menu, modelled on Hyprland's {@code general}, {@code decoration} and {@code animations}
 * sections. {@link #settings} is the theme itself: every value a theme file holds, written whenever a theme is
 * applied and saved back into the active theme when edited. {@link #preferences} are personal options that stay the
 * same whichever theme is active (UI scale, input).
 */
public final class ThemeSettings {
	public enum Font {
		SANS, MONO
	}

	public enum WorkspaceAnimation {
		SLIDE, SLIDE_VERTICAL, FADE, NONE
	}

	public enum ModKey {
		ALT, SUPER, CTRL
	}

	public enum BarPosition {
		TOP, BOTTOM
	}

	public enum Curve {
		EASE_OUT_QUINT(Bezier.EASE_OUT_QUINT), EASE_OUT_EXPO(Bezier.EASE_OUT_EXPO), EASE_IN_OUT_CUBIC(Bezier.EASE_IN_OUT_CUBIC),
		OVERSHOT(Bezier.OVERSHOT), SMOOTH(Bezier.SMOOTH), LINEAR(Bezier.LINEAR);

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
	public final DoubleSetting uiScale = spGeneral.doubleSetting("UI Scale").description("Multiplier on the automatic UI scale.").defaultValue(1.0).range(0.5, 3).decimals(2).build();
	public final BoolSetting titleScreen = spGeneral.bool("Title Screen")
		.description("Myriad's own title screen, in the theme's colours. Off: Minecraft's, with Myriad's logo and keys in a corner.").defaultValue(true).build();
	public final BoolSetting pauseGame = spGeneral.bool("Pause Game").description("Pause singleplayer while the menu is open.").defaultValue(false).build();

	private final SettingGroup sgGeneral = settings.group("General");
	public final EnumSetting<Font> font = sgGeneral.enumSetting("Font", Font.SANS).build();
	public final DoubleSetting fontSize = sgGeneral.doubleSetting("Font Size").defaultValue(7.5).range(5, 14).decimals(1).build();
	public final IntSetting gapsIn = sgGeneral.intSetting("Gaps In").description("Gap between tiled windows.").defaultValue(3).range(0, 40).build();
	public final IntSetting gapsOut = sgGeneral.intSetting("Gaps Out").description("Gap between windows and the screen edge.").defaultValue(6).range(0, 80).build();
	public final IntSetting borderSize = sgGeneral.intSetting("Border Size").defaultValue(1).range(0, 6).build();
	public final BoolSetting titleBars = sgGeneral.bool("Title Bars").description("Show a title bar on windows (drag it to move floating windows).").defaultValue(true).build();

	// The defaults are Myriad's own look, the logo's: its mint on its near-black, with neutral greys between.
	private final SettingGroup sgColors = settings.group("Colors");
	public final ColorSetting accent = sgColors.color("Accent").defaultValue(0xFF7FD8AE).build();
	public final ColorSetting secondary = sgColors.color("Secondary").description("Second accent, used for gradients.").defaultValue(0xFF4FA884).build();
	public final ColorSetting activeBorderFrom = sgColors.color("Active Border").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT)).build();
	public final ColorSetting activeBorderTo = sgColors.color("Active Border End").description("Second gradient stop.").defaultValue(SettingColor.role(SettingColor.Mode.SECONDARY)).build();
	public final DoubleSetting borderAngle = sgColors.doubleSetting("Border Angle").defaultValue(45).range(0, 360).decimals(0).build();
	public final BoolSetting animateBorder = sgColors.bool("Animate Border").description("Rotate the active border gradient.").defaultValue(false).build();
	public final ColorSetting inactiveBorder = sgColors.color("Inactive Border").defaultValue(0x60303633).build();
	public final ColorSetting windowBackground = sgColors.color("Window Background").defaultValue(0xD8131514).build();
	public final ColorSetting titleBackground = sgColors.color("Title Background").defaultValue(0x400C0C0C).build();
	public final ColorSetting surface = sgColors.color("Surface").description("Buttons, inputs and rows.").defaultValue(0x60252A27).build();
	public final ColorSetting surfaceHover = sgColors.color("Surface Hover").defaultValue(0x90343A37).build();
	public final ColorSetting text = sgColors.color("Text").defaultValue(0xFFE9EEEB).build();
	public final ColorSetting textDim = sgColors.color("Text Dim").defaultValue(0xFF8C9690).build();
	public final ColorSetting desktopTint = sgColors.color("Menu Tint").description("Drawn over the game behind the menu.").defaultValue(0x700C0C0C).build();
	public final ColorSetting barBackground = sgColors.color("Bar Background").defaultValue(0xE00C0C0C).build();

	private final SettingGroup sgPalette = settings.group("Palette");
	public final ColorSetting red = sgPalette.color("Red").description("Danger, hostile mobs, the Combat category.").defaultValue(0xFFF38BA8).build();
	public final ColorSetting green = sgPalette.color("Green").description("Success, passive mobs, the Player category.").defaultValue(0xFFA6E3A1).build();
	public final ColorSetting yellow = sgPalette.color("Yellow").description("Warnings, items, the World category.").defaultValue(0xFFF9E2AF).build();
	public final ColorSetting blue = sgPalette.color("Blue").defaultValue(0xFF89B4FA).build();
	public final ColorSetting magenta = sgPalette.color("Magenta").defaultValue(0xFFCBA6F7).build();
	public final ColorSetting cyan = sgPalette.color("Cyan").description("Friends.").defaultValue(0xFF94E2D5).build();

	private final SettingGroup sgDecoration = settings.group("Decoration");
	public final IntSetting rounding = sgDecoration.intSetting("Rounding").defaultValue(4).range(0, 30).build();
	public final BoolSetting blur = sgDecoration.bool("Blur").description("Frosted-glass window backgrounds.").defaultValue(true).build();
	public final IntSetting blurPasses = sgDecoration.intSetting("Blur Passes").defaultValue(3).range(1, 6).visible(blur::get).build();
	public final DoubleSetting blurSize = sgDecoration.doubleSetting("Blur Size").defaultValue(2.5).range(0.5, 8).decimals(1).visible(blur::get).build();
	public final BoolSetting shadow = sgDecoration.bool("Shadow").defaultValue(false).build();
	public final IntSetting shadowRange = sgDecoration.intSetting("Shadow Range").defaultValue(14).range(1, 60).visible(shadow::get).build();
	public final ColorSetting shadowColor = sgDecoration.color("Shadow Color").defaultValue(0x99000000).visible(shadow::get).build();
	public final DoubleSetting dimInactive = sgDecoration.doubleSetting("Dim Inactive").description("Darken unfocused windows.").defaultValue(0.0).range(0, 0.8).decimals(2).build();
	public final DoubleSetting inactiveOpacity = sgDecoration.doubleSetting("Inactive Opacity").defaultValue(1.0).range(0.2, 1).decimals(2).build();

	private final SettingGroup sgAnimations = settings.group("Animations");
	public final BoolSetting animations = sgAnimations.bool("Enabled").defaultValue(true).build();
	public final EnumSetting<Curve> curve = sgAnimations.enumSetting("Curve", Curve.EASE_OUT_QUINT).visible(animations::get).build();
	public final DoubleSetting windowSpeed = sgAnimations.doubleSetting("Window Speed").description("Duration in tenths of a second, like Hyprland.").defaultValue(3.79).range(0.5, 20).decimals(2).visible(animations::get).build();
	public final DoubleSetting workspaceSpeed = sgAnimations.doubleSetting("Workspace Speed").defaultValue(3.0).range(0.5, 20).decimals(1).visible(animations::get).build();
	public final EnumSetting<WorkspaceAnimation> workspaceAnimation = sgAnimations.enumSetting("Workspace Style", WorkspaceAnimation.SLIDE).visible(animations::get).build();
	public final DoubleSetting popin = sgAnimations.doubleSetting("Popin").description("Scale windows grow from when opening (Hyprland's popin %).").defaultValue(0.87).range(0.5, 1).decimals(2).visible(animations::get).build();
	public final DoubleSetting borderSpeed = sgAnimations.doubleSetting("Border Speed").description("Seconds per border gradient rotation.").defaultValue(6).range(1, 60).decimals(1).visible(() -> animations.get() && animateBorder.get()).build();

	private final SettingGroup sgBar = settings.group("Bar");
	public final BoolSetting bar = sgBar.bool("Show Bar").defaultValue(true).build();
	public final EnumSetting<BarPosition> barPosition = sgBar.enumSetting("Position", BarPosition.TOP).visible(bar::get).build();
	public final IntSetting barHeight = sgBar.intSetting("Height").defaultValue(14).range(10, 40).visible(bar::get).build();

	private final SettingGroup spCosmetics = preferences.group("Cosmetics");
	public final BoolSetting myriadCape = spCosmetics.bool("Myriad Cape")
		.description("Wear Myriad's cape and elytra in place of your own, seen by you and by Myriad players near you. Off: they see your own cape again; you still see theirs.")
		.defaultValue(true).onChanged(v -> {
			// Tell the server at once, so players near you see the change.
			var mc = net.minecraft.client.Minecraft.getInstance();
			if (mc.player != null) mc.options.broadcastOptions();
		}).build();

	private final SettingGroup sgInput = preferences.group("Input");
	public final EnumSetting<ModKey> modKey = sgInput.enumSetting("Mod Key", ModKey.ALT)
		.description("Modifier for menu shortcuts (Omarchy's Super). Hyprland keeps Super for itself, so Alt is the default.").build();
	public final BoolSetting vimKeys = sgInput.bool("Vim Keys").description("h/j/k/l, gg/G and Ctrl+d/u move the selection inside windows (arrow keys always work).").defaultValue(true).build();

	private final SettingGroup sgHud = settings.group("HUD");
	public final BoolSetting hudBackground = sgHud.bool("HUD Backgrounds").description("Draw a translucent card behind HUD elements.").defaultValue(false).build();
	public final IntSetting hudPadding = sgHud.intSetting("HUD Edge Padding").defaultValue(4).range(0, 40).build();

	/** Raw colour for a theme role (never a role itself, so roles can't recurse). */
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

	public FontFamily fontFamily() {
		return font.get() == Font.MONO ? FontFamily.MONO : FontFamily.SANS;
	}

	/** Animation duration in ms for a Hyprland-style speed (tenths of a second), or 0 if animations are off. */
	public float durationMs(DoubleSetting speed) {
		return animations.get() ? (float) (speed.get() * 100) : 0;
	}

	public Bezier bezier() {
		return curve.get().bezier;
	}
}
