package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Canvas;
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
		c.text(c.ellipsize(c.defaultFont(), c.defaultFontSize(), setting.name(), labelW), x + 2, y + (ROW - c.textHeight()) / 2, color);
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
