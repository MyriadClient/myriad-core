package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.util.ColorUtil;

/**
 * A setting's name (left) and editor (right). Wide editors (pickers, lists) go below the name instead. Right-click
 * the name to reset to default.
 */
public class SettingRow extends Widget {
	private final Setting<?> setting;
	private final Widget editor;
	private final boolean stacked;

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

	private float split(float width) {
		return Math.min(width * 0.5f, 110);
	}

	@Override
	protected float measure(Canvas c, float width) {
		if (stacked) return ROW + editor.layout(c, x, y + ROW, width);
		float labelW = width - split(width);
		return Math.max(ROW, editor.layout(c, x + labelW, y, width - labelW));
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		boolean hover = mx >= x && my >= y && mx < x + width && my < y + ROW;
		if (hover) c.roundRect(x - 2, y, width + 4, ROW, 3, ColorUtil.withAlpha(theme().surface.argb(), ColorUtil.alpha(theme().surface.argb()) / 2));
		int color = setting.isDefault() ? theme().text.argb() : ColorUtil.lerp(theme().text.argb(), theme().accent.argb(), 0.6f);
		float labelW = stacked ? width : width - split(width) - 4;
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
		float labelW = stacked ? width : width - split(width);
		if (button == 1 && mx >= x && mx < x + labelW && my >= y && my < y + ROW) {
			setting.reset();
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
		return stacked ? height : Math.max(ROW, Math.min(height, ROW + 2));
	}

	@Override
	public boolean mouseScrolled(float mx, float my, float amount) {
		return editor.isHovered(mx, my) && editor.mouseScrolled(mx, my, amount);
	}
}
