package dev.myriadclient.impl.render.font;

import dev.myriadclient.api.render.FontFamily;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Font;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads the bundled TTFs and caches {@link SizedFont}s per (family, pixel size). Text is rasterised at the exact
 * pixel size it is drawn at, so it stays crisp at any UI scale.
 */
public final class FontManager {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Fonts");
	private static final int MAX_CACHED = 24;

	private final Map<String, Font[]> chains = new HashMap<>();
	private final LinkedHashMap<String, SizedFont> cache = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, SizedFont> eldest) {
			if (size() > MAX_CACHED) {
				eldest.getValue().delete();
				return true;
			}
			return false;
		}
	};

	public FontManager() {
		Font mono = load("mono.ttf");
		Font dialog = new Font(Font.DIALOG, Font.PLAIN, 12);
		chains.put(FontFamily.SANS.id(), new Font[]{load("ui-regular.ttf"), mono, dialog});
		chains.put(FontFamily.SANS_BOLD.id(), new Font[]{load("ui-semibold.ttf"), mono, dialog});
		chains.put(FontFamily.MONO.id(), new Font[]{mono, dialog});
	}

	private static Font load(String file) {
		try (InputStream in = FontManager.class.getResourceAsStream("/assets/myriad/font/" + file)) {
			if (in == null) throw new IllegalStateException("missing");
			return Font.createFont(Font.TRUETYPE_FONT, in);
		} catch (Exception e) {
			LOG.error("Failed to load font {}, falling back to Dialog", file, e);
			return new Font(Font.DIALOG, Font.PLAIN, 12);
		}
	}

	/** Registers an extra family (e.g. from an addon); {@code fonts} is the fallback chain, primary first. */
	public void register(FontFamily family, Font... fonts) {
		chains.put(family.id(), fonts);
	}

	public SizedFont get(FontFamily family, int pixelSize) {
		pixelSize = Math.clamp(pixelSize, 4, 128);
		String key = family.id() + "@" + pixelSize;
		SizedFont f = cache.get(key);
		if (f == null) {
			Font[] chain = chains.getOrDefault(family.id(), chains.get(FontFamily.SANS.id()));
			f = new SizedFont(chain, pixelSize);
			cache.put(key, f);
		}
		return f;
	}
}
