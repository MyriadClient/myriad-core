package com.example.myriadaddon.bar;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.ui.BarWidget;
import dev.myriadclient.api.util.MyriadId;

/**
 * A widget on the menu's top bar showing the server's TPS from {@code Myriad.server()}, coloured with the theme's
 * palette (green, yellow, red) so it fits any theme. Bar widgets are ordered by {@code order} within their side.
 */
public final class TpsBarWidget extends BarWidget {
	public TpsBarWidget(MyriadId id) {
		super(id, "Server TPS", Side.RIGHT, 40);
	}

	private String text() {
		return " " + String.format("%.1f", Myriad.server().tps());
	}

	private float size(Canvas c) {
		return c.defaultFontSize() * 0.9f;
	}

	@Override
	public float width(Canvas c, float height) {
		return c.textWidth(FontFamily.MONO, size(c), text());
	}

	@Override
	public void render(Canvas c, float x, float y, float w, float h, float mx, float my) {
		var theme = Myriad.ui().theme();
		float tps = Myriad.server().tps();
		int color = tps >= 18 ? theme.green.argb() : tps >= 12 ? theme.yellow.argb() : theme.red.argb();
		c.text(FontFamily.MONO, size(c), text(), x, y + (h - c.textHeight(FontFamily.MONO, size(c))) / 2, color);
	}
}
