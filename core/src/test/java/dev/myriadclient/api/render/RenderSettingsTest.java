package dev.myriadclient.api.render;

import com.google.gson.JsonParser;
import dev.myriadclient.FakeApi;
import dev.myriadclient.api.setting.SavedSettings;
import dev.myriadclient.api.setting.Settings;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A module's Render options: where they start, what a box gets from them, and what older saves carry over. */
class RenderSettingsTest {
	@BeforeEach
	void install() {
		FakeApi.install();
	}

	@AfterEach
	void uninstall() {
		FakeApi.uninstall();
	}

	@Test
	void startsAsAnOutlineWithAFaintFill() {
		RenderSettings r = RenderSettings.builder(new Settings().group("Render")).build();
		assertSame(HighlightStyle.Fill.SOLID, r.fill());
		assertEquals("Solid", r.fill.get());
		assertEquals(0.2, r.fillOpacity.get());
		assertEquals(1.5, r.lineWidth.get());
		assertTrue(r.throughWalls());
		assertEquals(2, r.style().outlineWidth());
	}

	@Test
	void builderStartsFromTheGivenLook() {
		RenderSettings r = RenderSettings.builder(new Settings().group("Render"))
			.defaults(HighlightStyle.OUTLINE.withFill(HighlightStyle.Fill.DOTS).withFillOpacity(0.15f).withGlow(5))
			.lineWidth(0).throughWalls(false).build();
		assertEquals("Dots", r.fill.get());
		// Rounded to the setting's decimals, not 0.15000000596.
		assertEquals(0.15, r.fillOpacity.get());
		assertEquals(5, r.glow.get());
		assertFalse(r.throughWalls());
		assertEquals(0, r.boxLine(0xFFFFFFFF, 1), "no edges at line width 0");
	}

	@Test
	void namedLooksShareAGroup() {
		Settings settings = new Settings();
		RenderSettings hand = RenderSettings.builder(settings.group("Render")).named("Hand").noThroughWalls().build();
		RenderSettings item = RenderSettings.builder(settings.group("Render")).named("Item").build();
		assertEquals("Hand Fill", hand.fill.name());
		assertEquals("Item Glow", item.glow.name());
		assertNull(hand.throughWalls);
		assertTrue(hand.throughWalls(), "keeps its default's");
	}

	@Test
	void boxColoursFollowTheFill() {
		RenderSettings r = RenderSettings.builder(new Settings().group("Render")).build();
		r.fillOpacity.set(0.5);
		assertEquals(0x40FF0000, r.boxFill(0x80FF0000, 1));
		assertEquals(0x20FF0000, r.boxFill(0x80FF0000, 0.5f));
		assertEquals(0x80FF0000, r.boxLine(0x80FF0000, 1));
		r.fill.set("None");
		assertEquals(0, r.boxFill(0xFFFF0000, 1));
	}

	@Test
	void aFillThatsGoneReadsAsNone() {
		RenderSettings r = RenderSettings.builder(new Settings().group("Render")).build();
		r.fill.set("Sparkles");
		assertSame(HighlightStyle.Fill.NONE, r.fill());
	}

	@Test
	void addonFillsAreListedByName() {
		HighlightStyle.Fill stripes = HighlightStyle.Fill.custom(Identifier.fromNamespaceAndPath("test", "highlight/diagonal_stripes"));
		assertEquals("Diagonal stripes", stripes.name());
		assertSame(stripes, HighlightStyle.Fill.byName("diagonal STRIPES"));
		assertTrue(HighlightStyle.Fill.all().contains(stripes));
		HighlightStyle.Fill dots = HighlightStyle.Fill.custom(Identifier.fromNamespaceAndPath("other", "dots"), "Dots");
		assertEquals("Dots (other)", dots.name(), "a name taken by another fill gets its namespace");
		RenderSettings r = RenderSettings.builder(new Settings().group("Render")).build();
		r.fill.set(stripes.name());
		assertSame(stripes, r.fill());
	}

	@Test
	void migratesTheBoxAndHighlightOptions() {
		SavedSettings saved = new SavedSettings(JsonParser.parseString("""
			{"render": {"shape": "LINES", "dot_spacing": 4.0, "dot_size": 2.0},
			 "hand": {"hand_shape": "FILL", "hand_fill": "DOTS"}}""").getAsJsonObject());
		RenderSettings.migrate(saved, "Render", null);
		RenderSettings.migrate(saved, "Hand", "Hand");
		assertEquals("None", saved.get("Render", "Fill").orElseThrow().getAsString());
		assertTrue(saved.get("Render", "Shape").isEmpty());
		assertEquals(4.0, saved.get("Render", "Pattern Spacing").orElseThrow().getAsDouble());
		assertEquals(2.0, saved.get("Render", "Pattern Size").orElseThrow().getAsDouble());
		assertTrue(saved.get("Render", "Line Width").isEmpty(), "edges kept");
		// The shader fill wins over the box's shape; faces only means no edges.
		assertEquals("Dots", saved.get("Hand", "Hand Fill").orElseThrow().getAsString());
		assertEquals(0, saved.get("Hand", "Hand Line Width").orElseThrow().getAsInt());
	}
}
