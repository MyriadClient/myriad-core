package dev.myriadclient.impl.render.font;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.myriadclient.impl.render.GpuGarbage;
import org.lwjgl.system.MemoryUtil;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/**
 * 256 consecutive code points rasterised into one texture. Each glyph gets a
 * cell sized to its actual pixel bounds, packed in shelves, and is drawn with the first font in the fallback chain
 * that can display it.
 */
final class GlyphPage {
	static final int PAD = 2;
	private static final int ATLAS_WIDTH = 1024;

	final Glyph[] glyphs = new Glyph[256];
	private GpuTexture texture;
	private GpuTextureView view;

	/** Private-use code points are icon glyphs (Nerd Fonts); they get an advance wide enough for their ink. */
	static boolean isIcon(int cp) {
		return (cp >= 0xE000 && cp <= 0xF8FF) || cp >= 0xF0000;
	}

	GlyphPage(int page, Font[] chain, int ascent, Graphics2D measure) {
		FontRenderContext frc = measure.getFontRenderContext();
		int base = page << 8;

		Font[] chosen = new Font[256];
		String[] text = new String[256];
		Rectangle[] bounds = new Rectangle[256];
		float[] advance = new float[256];
		int[] gx = new int[256], gy = new int[256], gw = new int[256], gh = new int[256];
		int x = 0, y = 0, rowH = 0;
		for (int i = 0; i < 256; i++) {
			int cp = base + i;
			if (!Character.isValidCodePoint(cp) || Character.isISOControl(cp)) continue;
			Font f = pick(chain, cp);
			if (f == null) continue;
			chosen[i] = f;
			text[i] = new String(Character.toChars(cp));
			GlyphVector gv = f.createGlyphVector(frc, text[i]);
			Rectangle b = gv.getPixelBounds(frc, 0, 0);
			bounds[i] = b;
			advance[i] = (float) gv.getGlyphMetrics(0).getAdvanceX();
			if (isIcon(cp) && !b.isEmpty()) advance[i] = Math.max(advance[i], b.x + b.width + 1);
			if (b.isEmpty()) continue;
			int w = b.width + PAD * 2, h = b.height + PAD * 2;
			if (x + w > ATLAS_WIDTH) {
				x = 0;
				y += rowH;
				rowH = 0;
			}
			gx[i] = x;
			gy[i] = y;
			gw[i] = w;
			gh[i] = h;
			x += w;
			rowH = Math.max(rowH, h);
		}
		int height = Math.max(1, y + rowH);

		BufferedImage img = new BufferedImage(ATLAS_WIDTH, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		hints(g);
		g.setColor(Color.WHITE);
		for (int i = 0; i < 256; i++) {
			if (chosen[i] == null || bounds[i].isEmpty()) continue;
			g.setFont(chosen[i]);
			g.drawString(text[i], gx[i] + PAD - bounds[i].x, gy[i] + PAD - bounds[i].y);
		}
		g.dispose();

		texture = upload(img);
		view = RenderSystem.getDevice().createTextureView(texture);
		for (int i = 0; i < 256; i++) {
			if (chosen[i] == null) continue;
			Rectangle b = bounds[i];
			boolean visible = !b.isEmpty();
			glyphs[i] = new Glyph(view,
				gx[i] / (float) ATLAS_WIDTH, gy[i] / (float) height,
				(gx[i] + gw[i]) / (float) ATLAS_WIDTH, (gy[i] + gh[i]) / (float) height,
				gw[i], gh[i], advance[i], visible,
				b.x - PAD, ascent + b.y - PAD);
		}
	}

	static void hints(Graphics2D g) {
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
	}

	private static Font pick(Font[] chain, int cp) {
		for (Font f : chain) if (f.canDisplay(cp)) return f;
		return null;
	}

	private static GpuTexture upload(BufferedImage img) {
		int w = img.getWidth(), h = img.getHeight();
		int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
		ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
		try {
			for (int p : argb) {
				buf.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) (p >>> 24));
			}
			buf.flip();
			GpuTexture tex = RenderSystem.getDevice().createTexture("Myriad glyphs", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
				GpuFormat.RGBA8_UNORM, w, h, 1, 1);
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(tex, buf, 0, 0, 0, 0, w, h);
			return tex;
		} finally {
			MemoryUtil.memFree(buf);
		}
	}

	void delete() {
		GpuGarbage.close(view);
		GpuGarbage.close(texture);
		view = null;
		texture = null;
	}
}
