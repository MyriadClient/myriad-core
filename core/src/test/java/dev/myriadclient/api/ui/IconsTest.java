package dev.myriadclient.api.ui;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every ready-made icon is in the icon font the UI draws with, once, and can be found by its name. */
class IconsTest {
	@Test
	void everyIconIsInTheBundledFont() throws Exception {
		Font font;
		try (InputStream in = IconsTest.class.getResourceAsStream("/assets/myriad/font/mono.ttf")) {
			font = Font.createFont(Font.TRUETYPE_FONT, in);
		}
		List<String> missing = new ArrayList<>();
		for (Icons.Icon i : Icons.all()) if (!font.canDisplay(i.glyph().codePointAt(0))) missing.add(i.name());
		assertTrue(missing.isEmpty(), "Not in mono.ttf: " + missing);
	}

	@Test
	void glyphsAndNamesAreUnique() {
		Set<String> glyphs = new HashSet<>(), names = new HashSet<>();
		for (Icons.Icon i : Icons.all()) {
			assertTrue(glyphs.add(i.glyph()), "Listed twice: " + i.name());
			assertTrue(names.add(i.name()), "Name used twice: " + i.name());
		}
	}

	@Test
	void searchMatchesWordStarts() {
		assertEquals(Icons.ROCKET, Icons.search("rock").getFirst().glyph());
		assertTrue(Icons.search("elytra").stream().anyMatch(i -> i.glyph().equals(Icons.PLANE)));
		assertTrue(Icons.search("ocket").isEmpty());
		assertFalse(Icons.search("").isEmpty());
	}
}
