package dev.myriadclient.impl.render.font;

import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.UnbakedGlyph;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EmptyGlyph;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;

/**
 * The theme's font as one of Minecraft's glyph providers, for its own text (the font {@code myriad:ui}, see ThemedText):
 * Myriad's TTF, measured and rasterised as Myriad's own text is (GlyphPage), at the GUI scale it's drawn at. Minecraft
 * rasterises a ttf provider once, at a fixed oversample; this one is told the scale ({@link #scale(int)}) and its font
 * set's baked glyphs dropped (ThemedText), not the resources reloaded. Glyphs are measured as they're first drawn at a
 * scale and kept, so going back to a scale only rasterises again what's drawn.
 */
public final class ThemeGlyphs implements GlyphProvider {
	/** The em in GUI pixels, and how far below Minecraft's baseline the glyphs sit, as ui.json had them for the ttf provider. */
	private static final float SIZE = 9.5f, SHIFT = 1f;
	/** Transparent pixels around each glyph's measured ink, for antialiasing that strays past it. */
	private static final int PAD = 1;

	private final Font font;
	private final IntSet supported;
	private final Int2ObjectMap<Scaled> scales = new Int2ObjectOpenHashMap<>();
	private volatile Scaled current;

	public ThemeGlyphs(String file, int scale) {
		font = FontManager.load(file);
		IntSet cps = new IntOpenHashSet();
		for (int cp = 0; cp <= 0xFFFF; cp++)
			if (!Character.isISOControl(cp) && !Character.isSurrogate((char) cp) && font.canDisplay(cp)) cps.add(cp);
		supported = IntSets.unmodifiable(cps);
		current = new Scaled(scale);
		scales.put(scale, current);
	}

	/** The GUI scale glyphs are given at from now on; the font set has to drop the glyphs it baked at the old one. */
	public void scale(int scale) {
		current = scales.computeIfAbsent(scale, Scaled::new);
	}

	public int scale() {
		return current.scale;
	}

	@Override
	public @Nullable UnbakedGlyph getGlyph(int codepoint) {
		return current.glyph(codepoint);
	}

	@Override
	public IntSet getSupportedGlyphs() {
		return supported;
	}

	/** The font at one scale: each glyph measured when it's first asked for, and rasterised when it's baked. */
	private final class Scaled {
		final int scale;
		final Font sized;
		final FontRenderContext frc;
		private final Int2ObjectMap<UnbakedGlyph> glyphs = new Int2ObjectOpenHashMap<>();

		Scaled(int scale) {
			this.scale = scale;
			sized = font.deriveFont((float) Math.round(SIZE * scale));
			Graphics2D measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
			GlyphPage.hints(measure);
			frc = measure.getFontRenderContext();
			measure.dispose();
		}

		synchronized @Nullable UnbakedGlyph glyph(int cp) {
			UnbakedGlyph glyph = glyphs.get(cp);
			if (glyph == null && supported.contains(cp)) {
				char ch = (char) cp;
				GlyphVector gv = sized.createGlyphVector(frc, new char[]{ch});
				float advance = gv.getGlyphMetrics(0).getAdvanceX();
				Rectangle ink = gv.getGlyphPixelBounds(0, frc, 0, 0);
				glyph = ink.isEmpty() ? new EmptyGlyph(advance / scale) : new Glyph(ch, ink, advance);
				glyphs.put(cp, glyph);
			}
			return glyph;
		}

		private final class Glyph implements UnbakedGlyph, GlyphBitmap {
			private final char ch;
			private final Rectangle ink;
			private final GlyphInfo info;

			Glyph(char ch, Rectangle ink, float advance) {
				this.ch = ch;
				this.ink = ink;
				info = GlyphInfo.simple(advance / scale);
			}

			@Override
			public GlyphInfo info() {
				return info;
			}

			@Override
			public BakedGlyph bake(UnbakedGlyph.Stitcher stitcher) {
				return stitcher.stitch(info, this);
			}

			@Override
			public int getPixelWidth() {
				return ink.width + PAD * 2;
			}

			@Override
			public int getPixelHeight() {
				return ink.height + PAD * 2;
			}

			@Override
			public float getOversample() {
				return scale;
			}

			@Override
			public float getBearingLeft() {
				return (float) (ink.x - PAD) / scale;
			}

			@Override
			public float getBearingTop() {
				return (float) (PAD - ink.y) / scale - SHIFT;
			}

			@Override
			public boolean isColored() {
				return false;
			}

			@Override
			public void upload(int x, int y, GpuTexture texture) {
				int w = getPixelWidth(), h = getPixelHeight();
				BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = img.createGraphics();
				GlyphPage.hints(g);
				g.setColor(Color.WHITE);
				g.setFont(sized);
				g.drawString(String.valueOf(ch), PAD - ink.x, PAD - ink.y);
				g.dispose();
				int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
				try (NativeImage image = new NativeImage(NativeImage.Format.LUMINANCE, w, h, false)) {
					long at = image.getPointer();
					for (int i = 0; i < argb.length; i++) MemoryUtil.memPutByte(at + i, (byte) (argb[i] >>> 24));
					RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image, 0, 0, x, y);
				}
			}
		}
	}
}
