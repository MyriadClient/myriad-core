package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.draw.FontFamily;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * Stands in for a list with nothing in it yet: an icon, what's missing, and how it gets filled, centred and wrapped.
 *
 * <pre>{@code
 * if (waypoints.isEmpty()) content.add(new EmptyState(Icons.MAP_MARKER, "No waypoints", "Add one with .waypoint add."));
 * }</pre>
 */
@ApiStatus.AvailableSince("1.4")
public class EmptyState extends Widget {
	private final String icon, title, body;
	private List<String> titleLines = List.of(), bodyLines = List.of();

	/**
	 * @param icon  a large glyph at the top ({@link dev.myriadclient.api.ui.Icons})
	 * @param title what's missing, in bold
	 * @param body  how it gets filled, dim; empty for none
	 */
	public EmptyState(String icon, String title, String body) {
		this.icon = icon;
		this.title = title;
		this.body = body;
	}

	private static float bodySize(Canvas c) {
		return c.defaultFontSize() * 0.9f;
	}

	@Override
	protected float measure(Canvas c, float width) {
		float room = Math.max(1, width - 8);
		titleLines = Label.wrap(c, FontFamily.SANS_BOLD, c.defaultFontSize(), title, room);
		bodyLines = body.isEmpty() ? List.of() : Label.wrap(c, FontFamily.SANS, bodySize(c), body, room);
		return 8 + c.textHeight(FontFamily.MONO, c.defaultFontSize() * 1.8f) + 4
			+ titleLines.size() * (c.textHeight(FontFamily.SANS_BOLD, c.defaultFontSize()) + 1)
			+ (bodyLines.isEmpty() ? 0 : 2 + bodyLines.size() * (c.textHeight(FontFamily.SANS, bodySize(c)) + 1)) + 8;
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		float big = c.defaultFontSize() * 1.8f, cx = x + width / 2, ly = y + 8;
		c.text(FontFamily.MONO, big, icon, cx - c.textWidth(FontFamily.MONO, big, icon) / 2, ly, theme().textDim.argb());
		ly += c.textHeight(FontFamily.MONO, big) + 4;
		for (String line : titleLines) {
			c.text(FontFamily.SANS_BOLD, c.defaultFontSize(), line, cx - c.textWidth(FontFamily.SANS_BOLD, c.defaultFontSize(), line) / 2, ly, theme().text.argb());
			ly += c.textHeight(FontFamily.SANS_BOLD, c.defaultFontSize()) + 1;
		}
		ly += 2;
		float s = bodySize(c);
		for (String line : bodyLines) {
			c.text(FontFamily.SANS, s, line, cx - c.textWidth(FontFamily.SANS, s, line) / 2, ly, theme().textDim.argb());
			ly += c.textHeight(FontFamily.SANS, s) + 1;
		}
	}
}
