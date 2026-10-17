package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.Animated;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.service.Notifications;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.util.ColorUtil;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Toasts, stacked bottom-right, sliding in like mako/dunst notifications. */
public final class NotificationManager implements Notifications {
	private static final int MAX = 6;
	private static final float WIDTH = 150;

	private final List<Entry> entries = new ArrayList<>();

	@Override
	public synchronized void send(String title, String message, Level level, long durationMs, String key) {
		long now = System.currentTimeMillis();
		for (Entry e : entries) {
			// The same key replaces; the same notification again while it's still up is counted on it ("×3"), not stacked.
			boolean same = e.title.equals(title) && e.message.equals(message) && e.level == level;
			if (key != null ? key.equals(e.key) : e.key == null && same && now <= e.expires) {
				e.count = same ? e.count + 1 : 1;
				e.title = title;
				e.message = message;
				e.level = level;
				e.expires = now + durationMs;
				return;
			}
		}
		Entry e = new Entry(title, message, level, now + durationMs, key);
		entries.add(e);
		while (entries.size() > MAX) entries.removeFirst();
	}

	private static int levelColor(Level level, ThemeSettings theme) {
		return switch (level) {
			case INFO -> theme.accent.argb();
			case SUCCESS -> theme.green.argb();
			case WARNING -> theme.yellow.argb();
			case ERROR -> theme.red.argb();
		};
	}

	private static String levelIcon(Level level) {
		return switch (level) {
			case INFO -> "";
			case SUCCESS -> "";
			case WARNING -> "";
			case ERROR -> "";
		};
	}

	/** Draws the stack anchored to the bottom-right of a {@code screenW}×{@code screenH} (UI units) area. */
	public synchronized void render(Canvas c, float screenW, float screenH, float bottomInset) {
		if (entries.isEmpty()) return;
		ThemeSettings theme = Myriad.ui().theme();
		long now = System.currentTimeMillis();
		float y = screenH - bottomInset - 6;
		float size = c.defaultFontSize();
		for (Iterator<Entry> it = entries.reversed().iterator(); it.hasNext(); ) {
			Entry e = it.next();
			boolean expired = now > e.expires;
			e.slide.animateTo(expired ? 0 : 1, 260, theme.bezier());
			float t = e.slide.get();
			if (expired && t <= 0.01f) continue;
			List<String> lines = Label.wrap(c, FontFamily.SANS, size * 0.95f, e.message, WIDTH - 22);
			float lh = c.textHeight(FontFamily.SANS, size * 0.95f);
			float h = 8 + c.textHeight(FontFamily.SANS_BOLD, size) + 2 + lines.size() * lh + 6;
			float x = screenW - 6 - WIDTH * t;
			y -= h * Math.min(1, t * 1.5f);
			c.push();
			c.alpha(t);
			if (theme.shadow.get()) c.shadow(x, y, WIDTH, h, theme.rounding.get(), 10, ColorUtil.fade(theme.shadowColor.argb(), 0.7f));
			c.backdrop(x, y, WIDTH, h, theme.rounding.get(), 1);
			c.roundRect(x, y, WIDTH, h, theme.rounding.get(), theme.windowBackground.argb());
			int accent = levelColor(e.level, theme);
			c.roundRect(x, y, 2.5f, h, theme.rounding.get(), 0, 0, theme.rounding.get(), accent);
			c.text(FontFamily.MONO, size, levelIcon(e.level), x + 7, y + 6, accent);
			c.text(FontFamily.SANS_BOLD, size, e.title, x + 18, y + 6, theme.text.argb());
			if (e.count > 1) {
				String count = "×" + e.count;
				c.text(FontFamily.SANS, size * 0.95f, count, x + WIDTH - 6 - c.textWidth(FontFamily.SANS, size * 0.95f, count), y + 6, theme.textDim.argb());
			}
			float ly = y + 6 + c.textHeight(FontFamily.SANS_BOLD, size) + 2;
			for (String line : lines) {
				c.text(FontFamily.SANS, size * 0.95f, line, x + 18, ly, theme.textDim.argb());
				ly += lh;
			}
			c.pop();
			y -= 4;
		}
		entries.removeIf(e -> now > e.expires && e.slide.get() <= 0.01f);
	}

	private static final class Entry {
		String title, message, key;
		Level level;
		long expires;
		/** How many times it was sent while showing (see {@link #send}). */
		int count = 1;
		final Animated slide = new Animated(0);

		Entry(String title, String message, Level level, long expires, String key) {
			this.title = title;
			this.message = message;
			this.level = level;
			this.expires = expires;
			this.key = key;
		}
	}
}
