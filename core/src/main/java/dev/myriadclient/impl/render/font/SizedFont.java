package dev.myriadclient.impl.render.font;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** One font family at one pixel size. Pages are rasterised lazily on first use. */
public final class SizedFont {
	private final Font[] chain;
	private final FontMetrics metrics;
	private final Graphics2D measure;
	private final Int2ObjectOpenHashMap<GlyphPage> pages = new Int2ObjectOpenHashMap<>();
	public final int pixelSize;
	public final int ascent;
	public final int lineHeight;

	SizedFont(Font[] baseChain, int pixelSize) {
		this.pixelSize = pixelSize;
		this.chain = new Font[baseChain.length];
		for (int i = 0; i < baseChain.length; i++) chain[i] = baseChain[i].deriveFont((float) pixelSize);
		measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
		GlyphPage.hints(measure);
		metrics = measure.getFontMetrics(chain[0]);
		ascent = metrics.getAscent();
		lineHeight = metrics.getAscent() + metrics.getDescent();
	}

	public Glyph glyph(int codePoint) {
		int page = codePoint >>> 8;
		GlyphPage p = pages.get(page);
		if (p == null) {
			p = new GlyphPage(page, chain, ascent, measure);
			pages.put(page, p);
		}
		Glyph g = p.glyphs[codePoint & 255];
		return g != null || codePoint == '?' ? g : glyph('?');
	}

	/** Width in pixels, ignoring § formatting codes. */
	public float width(String text) {
		float w = 0;
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == '§' && i < text.length()) {
				i++;
				continue;
			}
			Glyph g = glyph(cp);
			if (g != null) w += g.advance();
		}
		return w;
	}

	void delete() {
		for (GlyphPage p : pages.values()) p.delete();
		pages.clear();
		measure.dispose();
	}
}
