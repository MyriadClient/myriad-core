package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.setting.StringListSetting;
import dev.myriadclient.api.util.ColorUtil;

import java.util.ArrayList;
import java.util.List;

/** Edits a {@link StringListSetting}: one chip per entry (click × to remove) plus an input to add. */
public class StringListEditor extends Widget {
	private final StringListSetting setting;
	private final TextField input;
	/** Remove-button hit boxes from the last render: x, y, w, h, index. */
	private final List<float[]> removeButtons = new ArrayList<>();

	public StringListEditor(StringListSetting setting) {
		this.setting = setting;
		this.input = new TextField(() -> "").placeholder("Add…").onSubmit(s -> {
			if (!s.isBlank()) setting.add(s.trim());
		});
	}

	@Override
	void attach(WidgetRoot root, Widget parent) {
		super.attach(root, parent);
		input.attach(root, this);
	}

	@Override
	protected float measure(Canvas c, float width) {
		float cx = 0, rows = setting.get().isEmpty() ? 0 : 1;
		for (String s : setting.get()) {
			float w = c.textWidth(s) + 16;
			if (cx + w > width && cx > 0) {
				rows++;
				cx = 0;
			}
			cx += w + 3;
		}
		float listH = rows * (ROW - 2 + 3);
		input.layout(c, x, y + listH, width);
		return listH + ROW;
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		float cx = x, cy = y;
		removeButtons.clear();
		int index = 0;
		for (String s : setting.get()) {
			float w = c.textWidth(s) + 16;
			if (cx + w > x + width && cx > x) {
				cx = x;
				cy += ROW - 2 + 3;
			}
			boolean h = mx >= cx + w - 11 && mx < cx + w && my >= cy && my < cy + ROW - 2;
			c.roundRect(cx, cy, w, ROW - 2, 4, theme().surface.argb());
			c.text(s, cx + 4, cy + (ROW - 2 - c.textHeight()) / 2, theme().text.argb());
			c.text("×", cx + w - 9, cy + (ROW - 2 - c.textHeight()) / 2, h ? ColorUtil.rgb(243, 139, 168) : theme().textDim.argb());
			removeButtons.add(new float[]{cx + w - 11, cy, 11, ROW - 2, index++});
			cx += w + 3;
		}
		input.render(c, mx, my);
	}

	@Override
	public boolean isNavigable() {
		return true;
	}

	@Override
	public void activate() {
		input.requestFocus();
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (input.mouseClicked(mx, my, button)) return true;
		if (!isHovered(mx, my)) return false;
		for (float[] b : removeButtons) {
			if (mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3]) {
				int index = (int) b[4];
				if (index < setting.get().size()) setting.remove(setting.get().get(index));
				return true;
			}
		}
		return true;
	}
}
