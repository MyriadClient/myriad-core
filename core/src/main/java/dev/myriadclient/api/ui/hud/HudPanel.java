package dev.myriadclient.api.ui.hud;

import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.ui.Panel;

/**
 * A base for HUD elements: a title and icon, a settings group named after the element with a {@link #scale} option,
 * and helpers for the things every HUD element deals with. Register one with
 * {@code ctx.registerHud("Name", icon, MyHud::new)}.
 *
 * <p>For elements made of text lines, extend {@link TextHudPanel} instead and only supply the lines.
 */
public abstract class HudPanel extends Panel {
	private final String title;
	private final String icon;
	/** The element's own settings group, named after it. */
	protected final SettingGroup sgGeneral;
	protected final DoubleSetting scale;

	protected HudPanel(String title, String icon) {
		this(title, icon, 1.0);
	}

	protected HudPanel(String title, String icon, double defaultScale) {
		this.title = title;
		this.icon = icon;
		this.sgGeneral = settings.group(title);
		this.scale = sgGeneral.decimal("Scale").defaultValue(defaultScale).range(0.5, 4).decimals(1).build();
	}

	@Override
	public String title() {
		return title;
	}

	@Override
	public String icon() {
		return icon;
	}

	/** The theme font size times {@link #scale}. */
	protected float fontSize(Canvas c) {
		return c.defaultFontSize() * scale.getFloat();
	}

	/** True when the element sits on the right of the screen, so text should grow leftwards. */
	protected boolean alignRight() {
		return window() != null && window().hudAlignX() > 0.5f;
	}

	/**
	 * True when there's no world, e.g. while arranging the HUD from the title screen. Show sample content then so the
	 * element can still be seen and placed.
	 */
	protected boolean preview() {
		return mc.player == null || mc.level == null;
	}
}
