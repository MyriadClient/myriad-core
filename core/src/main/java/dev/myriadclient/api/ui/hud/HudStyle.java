package dev.myriadclient.api.ui.hud;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.setting.Settings;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.util.ColorUtil;

/**
 * The colour options every text HUD element shares, as a "Colors" settings group: how values are coloured, a
 * separate colour for labels, the text shadow, and rainbow tuning. Use it so your HUD elements offer the same
 * choices as the stock ones. {@link TextHudPanel} sets one up for you.
 *
 * <p>{@code index}/{@code count} let a gradient or rainbow run across lines: pass the line's position.
 */
public final class HudStyle {
	public enum Mode {
		/** The module's category colour (module lists); other elements fall back to the accent. */
		CATEGORY,
		TEXT, ACCENT, GRADIENT, RAINBOW, CUSTOM
	}

	private final EnumSetting<Mode> mode;
	private final ColorSetting custom;
	private final ColorSetting labels;
	private final DoubleSetting rainbowSpeed;
	private final DoubleSetting rainbowSpread;
	private final BoolSetting shadow;

	/**
	 * @param withLabels whether the element draws labels (like "FPS") and should offer a colour for them
	 */
	public HudStyle(Settings settings, Mode defaultMode, boolean withLabels) {
		SettingGroup sg = settings.group("Colors");
		mode = sg.oneOf("Mode", defaultMode).description("How text is coloured. Category only applies to the module list.").build();
		custom = sg.color("Color").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT)).visible(() -> mode.get() == Mode.CUSTOM).build();
		labels = withLabels
			? sg.color("Labels").description("Colour of labels like \"FPS\".").defaultValue(SettingColor.role(SettingColor.Mode.TEXT, 0x99)).build()
			: null;
		rainbowSpeed = sg.decimal("Rainbow Speed").defaultValue(1).range(0.1, 5).decimals(1).visible(() -> mode.get() == Mode.RAINBOW).build();
		rainbowSpread = sg.decimal("Rainbow Spread").description("Hue shift between lines.").defaultValue(0.06).range(0, 0.3).decimals(2)
			.visible(() -> mode.get() == Mode.RAINBOW).build();
		shadow = sg.bool("Shadow").defaultValue(true).build();
	}

	public Mode mode() {
		return mode.get();
	}

	public boolean shadow() {
		return shadow.get();
	}

	/** Value colour for line {@code index} of {@code count}; {@code category} is used in CATEGORY mode. */
	public int color(int index, int count, int category) {
		ThemeSettings theme = Myriad.ui().theme();
		return switch (mode.get()) {
			case CATEGORY -> category != 0 ? category : theme.accent.argb();
			case TEXT -> theme.text.argb();
			case ACCENT -> theme.accent.argb();
			case GRADIENT -> ColorUtil.lerp(theme.activeBorderFrom.argb(), theme.activeBorderTo.argb(), count <= 1 ? 0 : index / (float) (count - 1));
			case RAINBOW -> ColorUtil.rainbow(rainbowSpeed.getFloat(), index * rainbowSpread.getFloat(), 0.6f, 1f, 255);
			case CUSTOM -> custom.argb();
		};
	}

	public int color(int index, int count) {
		return color(index, count, 0);
	}

	public int color(int index, int count, Module module) {
		return color(index, count, module.tag().color());
	}

	/** Label colour (the theme's dim text when this style has no label option). */
	public int label() {
		return labels != null ? labels.argb() : Myriad.ui().theme().textDim.argb();
	}

	/** Draws text with this style's shadow setting. Returns the width drawn. */
	public float text(Canvas c, FontFamily font, float size, String text, float x, float y, int color) {
		return draw(c, font, size, text, x, y, color, shadow.get());
	}

	/** Draws text with an optional HUD-style drop shadow. Returns the width drawn. */
	public static float draw(Canvas c, FontFamily font, float size, String text, float x, float y, int color, boolean shadow) {
		if (shadow) c.text(font, size, text, x + 0.6f, y + 0.6f, 0x99000000);
		return c.text(font, size, text, x, y, color);
	}
}
