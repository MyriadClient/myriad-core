package com.example.myriadaddon.hud;

import com.example.myriadaddon.stats.SessionStats;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.ui.hud.HudStyle;
import dev.myriadclient.api.ui.hud.TextHudPanel;
import dev.myriadclient.api.util.Format;

/**
 * A HUD element built on {@link TextHudPanel}: it only supplies lines. Sizing, right-alignment when placed on the
 * right of the screen, the Scale option and the standard colour options (the same ones every stock text element has)
 * come with the base class. Settings added to {@code sgGeneral} appear in the element's own group.
 */
public final class SessionStatsHud extends TextHudPanel {
	private final SessionStats stats;
	private final BoolSetting time = sgGeneral.bool("Time").defaultValue(true).build();
	private final BoolSetting distance = sgGeneral.bool("Distance").defaultValue(true).build();
	private final BoolSetting topSpeed = sgGeneral.bool("Top Speed").build();
	private final BoolSetting deaths = sgGeneral.bool("Deaths").defaultValue(true).build();

	public SessionStatsHud(SessionStats stats) {
		super("Session Stats", "", HudStyle.Mode.ACCENT);
		this.stats = stats;
	}

	@Override
	protected void lines(Lines out) {
		if (time.get()) {
			long s = stats.sessionMillis() / 1000;
			out.add("Session", String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60));
		}
		if (distance.get()) out.add("Travelled", Format.distance(stats.distance()));
		if (topSpeed.get()) out.add("Top Speed", String.format("%.1f b/s", stats.topSpeed()));
		if (deaths.get()) out.add("Deaths", String.valueOf(stats.deaths()));
	}
}
