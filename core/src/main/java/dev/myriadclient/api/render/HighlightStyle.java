package dev.myriadclient.api.render;

import dev.myriadclient.api.event.events.HighlightEvent;
import dev.myriadclient.impl.render.HighlightRenderer;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

import java.util.HashMap;
import java.util.Map;

/**
 * How a highlight looks: an outline traced around the silhouette of what's highlighted, an optional soft glow beyond
 * the outline, and an optional fill inside. Highlights are drawn by a screen-space shader from the exact silhouette
 * (models, armour, held items), so they hug the shape instead of its hitbox. Lengths are in scaled GUI pixels, so a
 * style looks the same at every GUI scale and resolution; outlines and glows of far things are drawn thinner (see
 * {@link #distanceScaling()}).
 * <p>
 * Styles are immutable: start from a preset and change what you need with the {@code with...} methods. Build one when
 * your settings change (or use {@link HighlightSettings}) rather than per entity.
 *
 * <pre>{@code
 * HighlightStyle style = HighlightStyle.OUTLINE.withGlow(6).withFill(HighlightStyle.Fill.DOTS);
 * }</pre>
 *
 * @see HighlightEvent
 */
public final class HighlightStyle {
	/** A crisp 2 px outline, through walls. */
	public static final HighlightStyle OUTLINE = new HighlightStyle(2, 0, 0.6f, Fill.NONE, 0.25f, 3, 1, 0, false, true, true);
	/** A thin outline with a soft 8 px glow, through walls. */
	public static final HighlightStyle GLOW = OUTLINE.withOutlineWidth(1).withGlow(8);

	private final float outlineWidth;
	private final float glow;
	private final float glowStrength;
	private final Fill fill;
	private final float fillOpacity;
	private final float dotSpacing;
	private final float dotSize;
	private final int gradient;
	private final boolean hasGradient;
	private final boolean throughWalls;
	private final boolean distanceScaling;

	private HighlightStyle(float outlineWidth, float glow, float glowStrength, Fill fill, float fillOpacity, float dotSpacing, float dotSize,
						   int gradient, boolean hasGradient, boolean throughWalls, boolean distanceScaling) {
		this.outlineWidth = clamp(outlineWidth, 0, 16);
		this.glow = clamp(glow, 0, 32);
		this.glowStrength = clamp(glowStrength, 0, 1);
		this.fill = fill == null ? Fill.NONE : fill;
		this.fillOpacity = clamp(fillOpacity, 0, 1);
		this.dotSpacing = clamp(dotSpacing, 2, 64);
		this.dotSize = clamp(dotSize, 0.5f, this.dotSpacing);
		this.gradient = gradient;
		this.hasGradient = hasGradient;
		this.throughWalls = throughWalls;
		this.distanceScaling = distanceScaling;
	}

	private static float clamp(float v, float min, float max) {
		return Float.isNaN(v) ? min : Math.clamp(v, min, max);
	}

	/** Outline width in scaled pixels; 0 draws no outline. At most 16. */
	public float outlineWidth() {
		return outlineWidth;
	}

	/** How far the glow reaches beyond the outline, in scaled pixels; 0 draws no glow. At most 32. */
	public float glow() {
		return glow;
	}

	/** How opaque the glow is where it starts (0..1); it fades out towards {@link #glow()}. */
	public float glowStrength() {
		return glowStrength;
	}

	/** What's drawn inside the silhouette. */
	public Fill fill() {
		return fill;
	}

	/** The fill's opacity (0..1), multiplied with the highlight colour's alpha. */
	public float fillOpacity() {
		return fillOpacity;
	}

	/** {@link Fill#DOTS}: distance between dot centres, in scaled pixels. */
	public float dotSpacing() {
		return dotSpacing;
	}

	/** {@link Fill#DOTS}: dot diameter, in scaled pixels. */
	public float dotSize() {
		return dotSize;
	}

	/**
	 * Whether the colour fades from the highlight's colour at the top to {@link #gradient()} at the bottom, across the
	 * outline, glow and fill alike.
	 */
	public boolean hasGradient() {
		return hasGradient;
	}

	/** The gradient's bottom colour (ARGB); its alpha is multiplied with the highlight colour's. */
	public int gradient() {
		return gradient;
	}

	/** Drawn even where something is in front; otherwise only the visible parts are highlighted. */
	public boolean throughWalls() {
		return throughWalls;
	}

	/**
	 * Whether the outline and glow get thinner with distance, so far things aren't buried under a border as thick as a
	 * near one's: full width within {@value #FULL_WIDTH_DISTANCE} blocks, then shrinking with the square root of the
	 * distance down to half width (at four times that distance), and never below one pixel. On by default.
	 */
	public boolean distanceScaling() {
		return distanceScaling;
	}

	/** How far the highlight is drawn at full width, in blocks; see {@link #distanceScaling()}. */
	public static final float FULL_WIDTH_DISTANCE = 10;

	/** The outline and glow width multiplier for something {@code distance} blocks from the camera. */
	public float widthScale(double distance) {
		if (!distanceScaling || distance <= FULL_WIDTH_DISTANCE) return 1;
		return (float) Math.max(0.5, Math.sqrt(FULL_WIDTH_DISTANCE / distance));
	}

	/** The furthest the highlight reaches beyond the silhouette, in scaled pixels (at full width). */
	public float reach() {
		return outlineWidth + glow;
	}

	public HighlightStyle withOutlineWidth(float outlineWidth) {
		return new HighlightStyle(outlineWidth, glow, glowStrength, fill, fillOpacity, dotSpacing, dotSize, gradient, hasGradient, throughWalls, distanceScaling);
	}

	public HighlightStyle withGlow(float glow) {
		return new HighlightStyle(outlineWidth, glow, glowStrength, fill, fillOpacity, dotSpacing, dotSize, gradient, hasGradient, throughWalls, distanceScaling);
	}

	public HighlightStyle withGlowStrength(float glowStrength) {
		return new HighlightStyle(outlineWidth, glow, glowStrength, fill, fillOpacity, dotSpacing, dotSize, gradient, hasGradient, throughWalls, distanceScaling);
	}

	public HighlightStyle withFill(Fill fill) {
		return new HighlightStyle(outlineWidth, glow, glowStrength, fill, fillOpacity, dotSpacing, dotSize, gradient, hasGradient, throughWalls, distanceScaling);
	}

	public HighlightStyle withFillOpacity(float fillOpacity) {
		return new HighlightStyle(outlineWidth, glow, glowStrength, fill, fillOpacity, dotSpacing, dotSize, gradient, hasGradient, throughWalls, distanceScaling);
	}

	/** {@link Fill#DOTS}: dot spacing and diameter in scaled pixels (the size is capped at the spacing). */
	public HighlightStyle withDots(float spacing, float size) {
		return new HighlightStyle(outlineWidth, glow, glowStrength, fill, fillOpacity, spacing, size, gradient, hasGradient, throughWalls, distanceScaling);
	}

	/** Fades the colour to {@code bottomColor} (ARGB) towards the bottom; see {@link #hasGradient()}. */
	public HighlightStyle withGradient(int bottomColor) {
		return new HighlightStyle(outlineWidth, glow, glowStrength, fill, fillOpacity, dotSpacing, dotSize, bottomColor, true, throughWalls, distanceScaling);
	}

	public HighlightStyle withoutGradient() {
		return new HighlightStyle(outlineWidth, glow, glowStrength, fill, fillOpacity, dotSpacing, dotSize, 0, false, throughWalls, distanceScaling);
	}

	public HighlightStyle withThroughWalls(boolean throughWalls) {
		return new HighlightStyle(outlineWidth, glow, glowStrength, fill, fillOpacity, dotSpacing, dotSize, gradient, hasGradient, throughWalls, distanceScaling);
	}

	public HighlightStyle withDistanceScaling(boolean distanceScaling) {
		return new HighlightStyle(outlineWidth, glow, glowStrength, fill, fillOpacity, dotSpacing, dotSize, gradient, hasGradient, throughWalls, distanceScaling);
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof HighlightStyle s && s.outlineWidth == outlineWidth && s.glow == glow && s.glowStrength == glowStrength
			&& s.fill == fill && s.fillOpacity == fillOpacity && s.dotSpacing == dotSpacing && s.dotSize == dotSize
			&& s.hasGradient == hasGradient && (!hasGradient || s.gradient == gradient) && s.throughWalls == throughWalls
			&& s.distanceScaling == distanceScaling;
	}

	@Override
	public int hashCode() {
		int h = Float.hashCode(outlineWidth);
		h = h * 31 + Float.hashCode(glow);
		h = h * 31 + Float.hashCode(glowStrength);
		h = h * 31 + fill.hashCode();
		h = h * 31 + Float.hashCode(fillOpacity);
		h = h * 31 + Float.hashCode(dotSpacing);
		h = h * 31 + Float.hashCode(dotSize);
		h = h * 31 + (hasGradient ? gradient : 1);
		h = h * 31 + Boolean.hashCode(throughWalls);
		return h * 31 + Boolean.hashCode(distanceScaling);
	}

	@Override
	public String toString() {
		return "HighlightStyle[outlineWidth=" + outlineWidth + ", glow=" + glow + ", glowStrength=" + glowStrength + ", fill=" + fill
			+ ", fillOpacity=" + fillOpacity + ", dotSpacing=" + dotSpacing + ", dotSize=" + dotSize
			+ (hasGradient ? ", gradient=#" + Integer.toHexString(gradient) : "") + ", throughWalls=" + throughWalls + ", distanceScaling=" + distanceScaling + "]";
	}

	/**
	 * What fills the silhouette: one of the built-in fills, or an addon's own ({@link #custom}).
	 */
	public static final class Fill {
		/** Nothing inside: outline and glow only. */
		public static final Fill NONE = new Fill("none", 0);
		/** One flat colour at {@link HighlightStyle#fillOpacity()}. */
		public static final Fill SOLID = new Fill("solid", 1);
		/** A grid of round dots ({@link HighlightStyle#dotSpacing()}, {@link HighlightStyle#dotSize()}) that moves with what's highlighted. */
		public static final Fill DOTS = new Fill("dots", 2);

		private static final Map<Identifier, Fill> CUSTOM = new HashMap<>();
		/** Addons' fills are numbered from here (the shaders tell them apart by number). */
		private static final int FIRST_CUSTOM = 16;

		private final String id;
		private final int shaderId;

		private Fill(String id, int shaderId) {
			this.id = id;
			this.shaderId = shaderId;
		}

		/**
		 * An addon's own fill, drawn by its fragment shader at {@code assets/<namespace>/shaders/<path>.fsh}. The shader
		 * imports Myriad's highlight composite and defines one function, which gets a pixel inside a highlight and
		 * returns its colour (alpha 0 leaves it clear); outlines and glow are drawn around it as for any fill:
		 * <pre>{@code
		 * #version 330
		 * #moj_import <myriad:highlight_fill.glsl>
		 *
		 * vec4 customFill(HighlightFill f) {
		 *     // f.pixel, f.local (from the highlight's bottom-left), f.size, f.color (gradient applied), f.opacity,
		 *     // f.spacing and f.dotSize (the style's dot settings, in pixels: use them as the pattern's scale),
		 *     // f.scale (pixels per GUI pixel), f.time (seconds)
		 *     float stripe = step(0.5, fract((f.local.x + f.local.y) / f.spacing));
		 *     return vec4(f.color.rgb, f.color.a * f.opacity * stripe);
		 * }
		 * }</pre>
		 * Create fills while your addon initialises (the shader is compiled with the game's own); the same
		 * {@code shader} always gives the same fill. Each fill in use adds one pass over its highlights' insides.
		 */
		public static synchronized Fill custom(Identifier shader) {
			Fill existing = CUSTOM.get(shader);
			if (existing != null) return existing;
			Fill fill = new Fill(shader.toString(), FIRST_CUSTOM + CUSTOM.size());
			CUSTOM.put(shader, fill);
			HighlightRenderer.registerFill(fill.shaderId, shader);
			return fill;
		}

		/** A stable name, for saving and commands: "none", "solid", "dots", or a custom fill's shader id. */
		public String id() {
			return id;
		}

		/** Whether this is an addon's fill ({@link #custom}). */
		public boolean isCustom() {
			return shaderId >= FIRST_CUSTOM;
		}

		/** The number the highlight shader switches on. */
		@ApiStatus.Internal
		public int shaderId() {
			return shaderId;
		}

		@Override
		public String toString() {
			return id;
		}
	}
}
