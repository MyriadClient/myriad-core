package dev.myriadclient.api.setting;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class SettingsTest {
	enum Mode {
		FAST, SLOW_AND_STEADY
	}

	static class Holder {
		final Settings settings = new Settings();
		final SettingGroup general = settings.group("General");
		final BoolSetting bool = general.bool("Rotate").defaultValue(true).build();
		final IntSetting count = general.integer("Count").defaultValue(3).range(0, 10).build();
		final DoubleSetting range = general.decimal("Range").defaultValue(4.5).range(0, 6).decimals(1).build();
		final EnumSetting<Mode> mode = general.oneOf("Mode", Mode.FAST).build();
		final SettingGroup colors = settings.group("Colors");
		final ColorSetting players = colors.color("Players").defaultValue(0xFF112233).build();
		final StringListSetting words = colors.stringList("Words").defaultValue("a", "b").build();
		// Same name as a setting in another group: allowed, ids are group-scoped.
		final BoolSetting otherRotate = colors.bool("Rotate").build();
	}

	@Test
	void onAnyChangedSeesEveryGroupOnlyOnRealChanges() {
		Holder h = new Holder();
		List<String> changed = new ArrayList<>();
		h.settings.onAnyChanged(s -> changed.add(s.id()));
		h.count.set(5);
		h.count.set(5); // unchanged: no call
		h.players.set(new SettingColor(0xFF000000, SettingColor.Mode.STATIC));
		h.words.add("z");
		// Added after the listener: still covered.
		BoolSetting late = h.colors.bool("Late").build();
		late.set(true);
		assertEquals(List.of("count", "players", "words", "late"), changed);
	}

	@Test
	void roundTripsThroughJson() {
		Holder a = new Holder();
		a.bool.set(false);
		a.count.set(7);
		a.range.set(5.25);
		a.mode.set(Mode.SLOW_AND_STEADY);
		a.players.set(new SettingColor(0x80FF0000, SettingColor.Mode.RAINBOW));
		a.words.add("c");
		a.otherRotate.set(true);
		JsonObject json = a.settings.toJson();

		Holder b = new Holder();
		b.settings.fromJson(JsonParser.parseString(json.toString()).getAsJsonObject());
		assertFalse(b.bool.get());
		assertEquals(7, b.count.get());
		assertEquals(5.3, b.range.get(), 1e-9, "rounded to 1 decimal");
		assertEquals(Mode.SLOW_AND_STEADY, b.mode.get());
		assertEquals(new SettingColor(0x80FF0000, SettingColor.Mode.RAINBOW), b.players.get());
		assertEquals(List.of("a", "b", "c"), b.words.get());
		assertTrue(b.otherRotate.get());
	}

	@Test
	void validationClampsAndBadJsonIsIgnored() {
		Holder h = new Holder();
		h.count.set(99);
		assertEquals(10, h.count.get());
		JsonObject bad = JsonParser.parseString("{\"general\":{\"count\":\"lots\",\"range\":2.0}}").getAsJsonObject();
		h.settings.fromJson(bad);
		// Unparseable values keep the current value; missing ones fall back to defaults.
		assertEquals(10, h.count.get());
		assertEquals(2.0, h.range.get());
		h.settings.fromJson(new JsonObject());
		assertEquals(3, h.count.get());
	}

	@Test
	void defaultsAreNotMutatedByCollectionEdits() {
		Holder h = new Holder();
		h.words.add("z");
		h.words.reset();
		assertEquals(List.of("a", "b"), h.words.get());
	}

	@Test
	void commandParsingAndKeys() {
		Holder h = new Holder();
		assertTrue(h.mode.parse("slowandsteady"));
		assertEquals(Mode.SLOW_AND_STEADY, h.mode.get());
		assertTrue(h.bool.parse("toggle"));
		assertFalse(h.count.parse("x"));
		assertEquals("general.rotate", h.settings.keyOf(h.bool));
		assertEquals("count", h.settings.keyOf(h.count));
		assertSame(h.otherRotate, h.settings.get("colors.rotate").orElseThrow());
		assertSame(h.bool, h.settings.get("rotate").orElseThrow());
	}

	@Test
	void duplicateIdInSameGroupIsRejected() {
		Settings s = new Settings();
		SettingGroup g = s.group("G");
		g.bool("Thing").build();
		assertThrows(IllegalArgumentException.class, () -> g.bool("thing").build());
	}

	@Test
	void listenersFireOnlyOnChange() {
		Holder h = new Holder();
		int[] n = {0};
		h.count.onChanged(v -> n[0]++);
		h.count.set(3);
		h.count.set(4);
		h.count.set(4);
		assertEquals(1, n[0]);
	}
}
