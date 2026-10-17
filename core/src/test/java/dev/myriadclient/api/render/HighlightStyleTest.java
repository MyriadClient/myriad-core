package dev.myriadclient.api.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Styles change one thing at a time and keep their values in range. */
class HighlightStyleTest {
	@Test
	void withersChangeOnlyTheirOption() {
		HighlightStyle s = HighlightStyle.OUTLINE.withGlow(6).withFill(HighlightStyle.Fill.DOTS).withThroughWalls(false);
		assertEquals(2, s.outlineWidth());
		assertEquals(6, s.glow());
		assertEquals(HighlightStyle.Fill.DOTS, s.fill());
		assertFalse(s.throughWalls());
		assertFalse(s.hasGradient());
		assertEquals(8, s.reach());
		assertEquals(HighlightStyle.OUTLINE, HighlightStyle.OUTLINE.withGlow(6).withGlow(0));
		assertNotEquals(HighlightStyle.OUTLINE, s);
	}

	@Test
	void gradientOnAndOff() {
		HighlightStyle g = HighlightStyle.OUTLINE.withGradient(0xFF00FF00);
		assertTrue(g.hasGradient());
		assertEquals(0xFF00FF00, g.gradient());
		assertEquals(HighlightStyle.OUTLINE, g.withoutGradient());
		assertEquals(HighlightStyle.OUTLINE.hashCode(), g.withoutGradient().hashCode());
	}

	@Test
	void farHighlightsAreThinnerButNotGone() {
		HighlightStyle s = HighlightStyle.OUTLINE;
		assertTrue(s.distanceScaling());
		assertEquals(1, s.widthScale(0));
		assertEquals(1, s.widthScale(HighlightStyle.FULL_WIDTH_DISTANCE));
		assertEquals(Math.sqrt(0.5), s.widthScale(HighlightStyle.FULL_WIDTH_DISTANCE * 2), 1e-6);
		assertEquals(0.5, s.widthScale(HighlightStyle.FULL_WIDTH_DISTANCE * 4), 1e-6);
		assertEquals(0.5, s.widthScale(500), 1e-6);
		assertEquals(1, s.withDistanceScaling(false).widthScale(500));
		assertNotEquals(s, s.withDistanceScaling(false));
	}

	@Test
	void valuesAreClamped() {
		HighlightStyle s = HighlightStyle.OUTLINE.withOutlineWidth(-3).withGlow(1000).withFillOpacity(2).withDots(1, 50).withGlowStrength(Float.NaN);
		assertEquals(0, s.outlineWidth());
		assertEquals(32, s.glow());
		assertEquals(1, s.fillOpacity());
		assertEquals(2, s.dotSpacing());
		assertEquals(2, s.dotSize());
		assertEquals(0, s.glowStrength());
		assertEquals(HighlightStyle.Fill.NONE, s.withFill(null).fill());
	}
}
