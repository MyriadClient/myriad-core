package dev.myriadclient.impl.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Highlight ids and merge groups survive the trip through an outline colour (and so through the mask's RGB). */
class HighlightRendererTest {
	@Test
	void idsAndGroupsRoundTripThroughOutlineColours() {
		for (int id : new int[]{0, 1, 255, 256, HighlightRenderer.MAX_HIGHLIGHTS - 1}) {
			for (int group : new int[]{1, 7, 255}) {
				int argb = HighlightRenderer.encode(id, group);
				assertEquals(0xFF, argb >>> 24, "opaque, so never 0 (vanilla's 'no outline')");
				assertEquals(id, HighlightRenderer.decodeId(argb));
				assertEquals(group, HighlightRenderer.decodeGroup(argb));
			}
		}
		// Id low byte in red, high byte in green, group in blue, as the shaders read them.
		assertEquals(0xFF010003, HighlightRenderer.encode(1, 3));
		assertEquals(0xFF000103, HighlightRenderer.encode(256, 3));
	}
}
