package dev.myriadclient.api.ui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.draw.FontFamily;
import dev.myriadclient.api.util.ColorUtil;

/**
 * A setting's name (left) and editor (right). Wide editors (pickers, lists) go below the name instead, and so does any
 * editor once the name and it don't both fit on a line. Right-click the name to reset to default; clicking a switch's
 * name flips the switch.
 */
public class SettingRow extends Widget {
	/** The least width of an editor beside its name; narrower than this it goes below. */
	private static final float MIN_EDITOR = 48;

	private final Setting<?> setting;
	private final Widget editor;
	private final boolean stacked;
	/** This frame's layout: the name above the editor, and how wide the name's column is. */
	private boolean below;
	private float nameW;

	/**
	 * A row for {@code setting} with {@code editor} as its editor (usually made for you by
	 * {@link dev.myriadclient.api.ui.SettingWidgets#create}). It shows only while the setting is visible, and its
	 * tooltip is the setting's description (and who's overriding it, if anyone is).
	 *
	 * @param stacked whether the editor always goes below the name
	 */
	public SettingRow(Setting<?> setting, Widget editor, boolean stacked) {
		this.setting = setting;
		this.editor = editor;
		this.stacked = stacked;
		this.visible = setting::isVisible;
		this.tooltip = setting.description();
	}

	@Override
	void attach(WidgetRoot root, Widget parent) {
		super.attach(root, parent);
		editor.attach(root, this);
	}

	@Override
	protected float measure(Canvas c, float width) {
		float wanted = c.textWidth(setting.name()) + 8 + (setting.overriddenBy() == null ? 0 : 24);
		if (editor instanceof Toggle) {
			below = false;
			nameW = width - 24;
			return Math.max(ROW, editor.layout(c, x + nameW, y, width - nameW));
		}
		float editorW = Math.min(width * 0.5f, 110);
		if (wanted > width - editorW) editorW = Math.max(MIN_EDITOR, width - wanted);
		below = stacked || wanted + MIN_EDITOR > width;
		nameW = below ? width : width - editorW;
		// A picker's list takes the whole row under its own line, its header staying beside the name.
		if (editor instanceof IconPicker picker) {
			picker.headerInset(below ? 0 : nameW);
			return below ? ROW + editor.layout(c, x, y + ROW, width) : editor.layout(c, x, y, width);
		}
		if (below) return ROW + editor.layout(c, x, y + ROW, width);
		return Math.max(ROW, editor.layout(c, x + nameW, y, width - nameW));
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		boolean hover = mx >= x && my >= y && mx < x + width && my < y + ROW;
		if (hover) c.roundRect(x - 2, y, width + 4, ROW, 3, ColorUtil.withAlpha(theme().surface.argb(), ColorUtil.alpha(theme().surface.argb()) / 2));
		int color = setting.isDefault() ? theme().text.argb() : ColorUtil.lerp(theme().text.argb(), theme().accent.argb(), 0.6f);
		float labelW = below ? width - 4 : nameW - 4;
		String by = setting.overriddenBy();
		float ty = y + (ROW - c.textHeight()) / 2;
		String name = c.ellipsize(c.defaultFont(), c.defaultFontSize(), setting.name(), by == null ? labelW : labelW * 0.6f);
		c.text(name, x + 2, ty, color);
		if (by != null) {
			// Who set the value in effect: a small tag after the name.
			float s = c.defaultFontSize() * 0.7f, tx = x + 2 + c.textWidth(name) + 4;
			String tag = c.ellipsize(FontFamily.SANS, s, by, Math.max(0, x + labelW - tx - 6));
			float tw = c.textWidth(FontFamily.SANS, s, tag) + 6, th = c.textHeight(FontFamily.SANS, s) + 3, cy = y + ROW / 2;
			c.roundRect(tx, cy - th / 2, tw, th, th / 2, ColorUtil.withAlpha(theme().accent.argb(), 50));
			c.text(FontFamily.SANS, s, tag, tx + 3, cy - c.textHeight(FontFamily.SANS, s) / 2, theme().accent.argb());
		}
		tooltip = by == null ? setting.description()
			: (setting.description().isEmpty() ? "" : setting.description() + "\n") + by + " sets this while it's on; your own value comes back after.";
		editor.render(c, mx, my);
		if (hover) offerTooltip(mx, my);
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (editor.mouseClicked(mx, my, button)) {
			if (root != null && root.pressed() == null) root.setPressed(editor);
			return true;
		}
		if (mx < x || mx >= x + nameW || my < y || my >= y + ROW) return false;
		if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
			setting.reset();
			return true;
		}
		if (button == InputConstants.MOUSE_BUTTON_LEFT && editor instanceof Toggle) {
			editor.activate();
			return true;
		}
		return false;
	}

	@Override
	public boolean isNavigable() {
		return true;
	}

	@Override
	public void activate() {
		editor.activate();
	}

	@Override
	public boolean adjust(int direction) {
		return editor.adjust(direction);
	}

	@Override
	public float navHeight() {
		return below ? height : Math.max(ROW, Math.min(height, ROW + 2));
	}

	@Override
	public boolean mouseScrolled(float mx, float my, float amount) {
		return editor.isHovered(mx, my) && editor.mouseScrolled(mx, my, amount);
	}
}
