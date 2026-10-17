package dev.myriadclient.api.setting;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Saving keeps what a module doesn't know, so moving between versions of it loses nothing. */
class KeepUnknownSettingsTest {
	static class Older {
		final Settings settings = new Settings();
		final SettingGroup general = settings.group("General");
		final DoubleSetting range = general.doubleSetting("Range").defaultValue(4.5).range(0, 6).build();
	}

	private static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void settingsFromANewerVersionSurviveASaveByAnOlderOne() {
		// Written by a newer version that added "Rotate" and a whole "Render" group.
		JsonObject saved = json("{\"general\":{\"range\":5.0,\"rotate\":true},\"render\":{\"color\":\"#FF0000FF\"}}");
		Older older = new Older();
		older.settings.fromJson(saved);
		assertEquals(5.0, older.range.get());
		older.range.set(3.0);
		JsonObject written = older.settings.toJson(saved);
		assertEquals(json("{\"general\":{\"range\":3.0,\"rotate\":true},\"render\":{\"color\":\"#FF0000FF\"}}"), written);
	}

	@Test
	void aKnownSettingBackAtItsDefaultIsDropped() {
		JsonObject saved = json("{\"general\":{\"range\":5.0}}");
		Older older = new Older();
		older.settings.fromJson(saved);
		older.range.reset();
		JsonObject written = older.settings.toJson(saved);
		assertFalse(written.has("general"), written.toString());
	}

	@Test
	void nothingSavedBeforeIsTheSameAsToJson() {
		Older older = new Older();
		older.range.set(1.0);
		assertEquals(older.settings.toJson(), older.settings.toJson(null));
	}
}
