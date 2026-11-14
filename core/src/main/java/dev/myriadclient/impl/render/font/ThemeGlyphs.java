package dev.myriadclient.impl.render.font;

import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.UnbakedGlyph;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.network.chat.Style;
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
	/** Where Minecraft's own capitals are centred, down its line (they stand on its baseline, 7 down, and are 7 high). */
	private static final float CAPS_MIDDLE = 3.5f;
	/** Transparent pixels around each glyph's measured ink, for antialiasing that strays past it. */
	private static final int PAD = 1;

	private final Font font;
	private final IntSet supported;
	/** How far below Minecraft's baseline the glyphs sit; NaN for wherever centres the capitals as Minecraft's are. */
	private final float shift;
	private final Int2ObjectMap<Scaled> scales = new Int2ObjectOpenHashMap<>();
	/** The em in GUI pixels. */
	private float size;
	private volatile Scaled current;

	public ThemeGlyphs(String file, int scale, float size, float shift) {
		font = FontManager.load(file);
		IntSet cps = new IntOpenHashSet();
		for (int cp = 0; cp <= 0xFFFF; cp++)
			if (!Character.isISOControl(cp) && !Character.isSurrogate((char) cp) && font.canDisplay(cp)) cps.add(cp);
		supported = IntSets.unmodifiable(cps);
		this.size = size;
		this.shift = shift;
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

	/** The em (GUI pixels) glyphs are given at from now on; as with {@link #scale(int)}, the font set has to drop its baked ones. */
	public void size(float size) {
		if (size == this.size) return;
		this.size = size;
		scales.clear();
		current = new Scaled(current.scale);
		scales.put(current.scale, current);
	}

	public float size() {
		return size;
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
		/** The shift, in whole pixels at this scale. */
		final float down;
		/**
		 * How far bold text's second copy and the shadow are drawn off, in GUI pixels: a screen pixel or two, where
		 * Minecraft's pixel font takes a whole GUI pixel (two or three screen pixels on a thin smooth face read as two lines).
		 */
		final float boldOffset, shadowOffset;
		private final Int2ObjectMap<UnbakedGlyph> glyphs = new Int2ObjectOpenHashMap<>();

		Scaled(int scale) {
			this.scale = scale;
			sized = font.deriveFont((float) Math.round(size * scale));
			Graphics2D measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
			GlyphPage.hints(measure);
			frc = measure.getFontRenderContext();
			measure.dispose();
			float caps = sized.createGlyphVector(frc, "H").getGlyphPixelBounds(0, frc, 0, 0).height / (float) scale;
			down = Math.round((Float.isNaN(shift) ? CAPS_MIDDLE + caps / 2 - 7 : shift) * scale) / (float) scale;
			boldOffset = Math.max(1, Math.round(scale * 0.4f)) / (float) scale;
			shadowOffset = Math.max(1, Math.round(scale * 0.5f)) / (float) scale;
		}

		/** A glyph's measures at this scale: its advance (GUI pixels) and the bold and shadow offsets. */
		GlyphInfo info(float advance) {
			return new GlyphInfo() {
				@Override
				public float getAdvance() {
					return advance;
				}

				@Override
				public float getBoldOffset() {
					return boldOffset;
				}

				@Override
				public float getShadowOffset() {
					return shadowOffset;
				}
			};
		}

		synchronized @Nullable UnbakedGlyph glyph(int cp) {
			UnbakedGlyph glyph = glyphs.get(cp);
			if (glyph == null && supported.contains(cp)) {
				char ch = (char) cp;
				GlyphVector gv = sized.createGlyphVector(frc, new char[]{ch});
				// The font's box for a character it hasn't got: Minecraft's font is asked instead.
				if (gv.getGlyphCode(0) == sized.getMissingGlyphCode()) return null;
				float advance = gv.getGlyphMetrics(0).getAdvanceX();
				Rectangle ink = gv.getGlyphPixelBounds(0, frc, 0, 0);
				glyph = ink.isEmpty() ? new Blank(info(advance / scale)) : new Glyph(ch, ink, advance);
				glyphs.put(cp, glyph);
			}
			return glyph;
		}

		/** A glyph with no ink (a space): only its advance. */
		private record Blank(GlyphInfo info) implements UnbakedGlyph {
			@Override
			public BakedGlyph bake(UnbakedGlyph.Stitcher stitcher) {
				return new BakedGlyph() {
					@Override
					public GlyphInfo info() {
						return info;
					}

					@Override
					public TextRenderable.@Nullable Styled createGlyph(float x, float y, int color, int shadowColor, Style style, float boldOffset,
						float shadowOffset) {
						return null;
					}
				};
			}
		}

		private final class Glyph implements UnbakedGlyph, GlyphBitmap {
			private final char ch;
			private final Rectangle ink;
			private final GlyphInfo info;

			Glyph(char ch, Rectangle ink, float advance) {
				this.ch = ch;
				this.ink = ink;
				info = Scaled.this.info(advance / scale);
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
				return (float) (PAD - ink.y) / scale - down;
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
