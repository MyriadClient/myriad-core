package dev.myriadclient.impl.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which fills frost over a background effect: one over another goes by their shapes, not their boxes. */
class FrostOverlapTest {
	/** A circle of radius {@code r} at ({@code cx}, {@code cy}) against another. */
	private static boolean circles(float ax, float ay, float ar, float bx, float by, float br) {
		return UiRenderer.roundedOverlap(ax - ar, ay - ar, ax + ar, ay + ar, ar, bx - br, by - br, bx + br, by + br, br);
	}

	@Test
	void wheelButtonsAroundTheDisc() {
		// A disc of 40 with buttons of 14 on a ring of 62, every 45 degrees: each button's box overlaps the disc's
		// box on the diagonals, but no button touches the disc, so every one frosts.
		for (int i = 0; i < 8; i++) {
			double a = Math.PI * 2 * i / 8;
			assertFalse(circles(0, 0, 40, (float) Math.cos(a) * 62, (float) Math.sin(a) * 62, 14), "button " + i);
		}
		// One that does reach into the disc doesn't.
		assertTrue(circles(0, 0, 40, 45, 0, 14));
	}

	@Test
	void rowsAndWhatsOnThem() {
		// Rows 18 tall with a 2 gap, corners 4: apart. A hover tint or a bar on a row: over it.
		assertFalse(UiRenderer.roundedOverlap(0, 0, 200, 18, 4, 0, 20, 200, 38, 4));
		assertTrue(UiRenderer.roundedOverlap(0, 0, 200, 18, 4, 2, 15, 198, 16, 0.5f));
		assertTrue(UiRenderer.roundedOverlap(0, 0, 200, 18, 4, -2, 0, 202, 18, 3));
	}

	@Test
	void roundCornersLeaveTheirCornerFree() {
		// A small square in the cut-off corner of a well rounded box isn't on it.
		assertFalse(UiRenderer.roundedOverlap(0, 0, 100, 100, 40, 0, 0, 8, 8, 0));
		assertTrue(UiRenderer.roundedOverlap(0, 0, 100, 100, 0, 0, 0, 8, 8, 0));
	}
}
