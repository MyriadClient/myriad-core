package dev.myriadclient.api.render;

/**
 * The Myriad mark (two interlocked rounded squares) drawn with SDF outlines, so it is crisp at any size. Matches
 * {@code assets/myriad/icon.png}, whose geometry is defined on a 512 grid.
 */
public final class MyriadLogo {
	private static final float STROKE = 30, HALO = 62, RADIUS = 34, FROM = 110, TO = 300, OFFSET = 102;

	private MyriadLogo() {
	}

	/** Draws the logo in a {@code size}×{@code size} box. {@code gap} should roughly match what's behind it. */
	public static void draw(Canvas c, float x, float y, float size, int color, int gap) {
		float k = size / 512f;
		square(c, x, y, k, 0, color, STROKE);
		square(c, x, y, k, OFFSET, gap, HALO);
		square(c, x, y, k, OFFSET, color, STROKE);
		// Where A's right side crosses B's top edge, A passes over B.
		c.push();
		c.clip(x + 245 * k, y + 157 * k, 110 * k, 110 * k);
		square(c, x, y, k, 0, gap, HALO);
		square(c, x, y, k, 0, color, STROKE);
		c.pop();
	}

	/** One rounded square; geometry is the stroke centre line on the 512 grid. */
	private static void square(Canvas c, float x, float y, float k, float offset, int color, float stroke) {
		float half = stroke / 2;
		float from = FROM + offset - half, size = TO - FROM + stroke;
		c.outline(x + from * k, y + from * k, size * k, size * k, (RADIUS + half) * k, stroke * k, color);
	}
}
