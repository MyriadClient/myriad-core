package dev.myriadclient.api.ui.draw;

/**
 * The Myriad mark (two interlocked rounded squares) drawn with SDF outlines, so it is crisp at any size. Matches
 * {@code assets/myriad/icon.png}, whose geometry is defined on a 512 grid.
 */
public final class MyriadLogo {
	private static final float STROKE = 30, HALO = 62, RADIUS = 34, FROM = 110, TO = 300, OFFSET = 102;

	private MyriadLogo() {
	}

	/**
	 * Draws the logo in a {@code size}&times;{@code size} box at ({@code x}, {@code y}), in the canvas's units, in
	 * {@code color} alone. Where one square passes under the other its stroke leaves a gap (it isn't drawn there), so
	 * nothing is painted over what's behind the logo. {@code gap} is no longer used: it was painted round the crossings.
	 */
	public static void draw(Canvas c, float x, float y, float size, int color, int gap) {
		float k = size / 512f;
		// A (offset 0) passes over B at A's right edge crossing B's top edge (300, 212); B passes over A at B's left edge
		// crossing A's bottom edge (212, 300). Each is drawn but for a box round where it passes under.
		float h = HALO / 2;
		except(c, x, y, k, OFFSET, color, 300 - h, 212 - h, 300 + h, 212 + h);
		except(c, x, y, k, 0, color, 212 - h, 300 - h, 212 + h, 300 + h);
	}

	/** One square, in {@code color}, everywhere but the box (x0, y0)-(x1, y1) on the 512 grid: four clipped passes round it. */
	private static void except(Canvas c, float x, float y, float k, float offset, int color, float x0, float y0, float x1, float y1) {
		float lo = FROM + offset - STROKE, hi = TO + offset + STROKE;
		float[][] parts = {{lo, lo, hi, y0}, {lo, y1, hi, hi}, {lo, y0, x0, y1}, {x1, y0, hi, y1}};
		for (float[] r : parts) {
			if (r[2] <= r[0] || r[3] <= r[1]) continue;
			c.push();
			c.clip(x + r[0] * k, y + r[1] * k, (r[2] - r[0]) * k, (r[3] - r[1]) * k);
			square(c, x, y, k, offset, color, STROKE);
			c.pop();
		}
	}

	/** One rounded square; geometry is the stroke centre line on the 512 grid. */
	private static void square(Canvas c, float x, float y, float k, float offset, int color, float stroke) {
		float half = stroke / 2;
		float from = FROM + offset - half, size = TO - FROM + stroke;
		c.outline(x + from * k, y + from * k, size * k, size * k, (RADIUS + half) * k, stroke * k, color);
	}
}
