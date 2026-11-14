package dev.myriadclient.api.ui.hud;

import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.ui.Panel;
import dev.myriadclient.api.ui.draw.Canvas;

/**
 * A base for HUD elements: a title and icon, a settings group named after the element with a {@link #scale} option,
 * and helpers for the things every HUD element deals with. Register one with
 * {@code ctx.registerHud("Name", icon, MyHud::new)}.
 * <p>
 * A HUD element is drawn in game while the menu is shut (not with the HUD hidden by F1), at the size its
 * {@link #preferredSize} gives, and while the HUD workspace is being edited, where players drag it into place and
 * right-click it for its settings. It gets no mouse or keyboard input. Size it from {@link #fontSize} so {@link #scale}
 * works, and answer the same size from {@link #preferredSize} as {@link #render} uses, since the element is placed by
 * it.
 *
 * <p>For elements made of text lines, extend {@link TextHudPanel} instead and only supply the lines.
 *
 * <pre>{@code
 * public final class CompassHud extends HudPanel {
 *     public CompassHud() { super("Compass", Icons.COMPASS); }
 *
 *     private String text() {
 *         return preview() ? "NORTH" : String.valueOf(mc.player.getDirection()).toUpperCase(Locale.ROOT);
 *     }
 *
 *     @Override
 *     public Rect preferredSize(Canvas c) {
 *         return new Rect(0, 0, c.textWidth(FontFamily.SANS_BOLD, fontSize(c), text()),
 *             c.textHeight(FontFamily.SANS_BOLD, fontSize(c)));
 *     }
 *
 *     @Override
 *     public void render(Canvas c, float width, float height, float mouseX, float mouseY) {
 *         HudStyle.draw(c, FontFamily.SANS_BOLD, fontSize(c), text(), 0, 0, Myriad.ui().theme().accent.argb(), true);
 *     }
 * }
 * }</pre>
 */
public abstract class HudPanel extends Panel {
	private final String title;
	private final String icon;
	/** The element's own settings group, named after it: add the element's options to it. */
	protected final SettingGroup sgGeneral;
	/** Scale (0.5-4) players set for the element; {@link #fontSize} applies it. Scale your own sizes by it too. */
	protected final DoubleSetting scale;

	/** An element with scale 1 by default. */
	protected HudPanel(String title, String icon) {
		this(title, icon, 1.0);
	}

	/**
	 * @param title        its name, also the name of its settings group
	 * @param icon         a Nerd Font glyph for it
	 * @param defaultScale the {@link #scale} it starts with
	 */
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

	/** The theme font size times {@link #scale}, in UI units. */
	protected float fontSize(Canvas c) {
		return c.defaultFontSize() * scale.getFloat();
	}

	/**
	 * True when the element sits on the right of the screen ({@link dev.myriadclient.api.ui.Window#hudAlignX()} past
	 * the middle), so text should grow leftwards.
	 */
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
