package dev.myriadclient.api.setting;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SavedSettingsTest {
	private static SavedSettings saved(String json) {
		return new SavedSettings(JsonParser.parseString(json).getAsJsonObject());
	}

	@Test
	void renameAcceptsCodeNamesOrIds() {
		SavedSettings s = saved("{\"general\": {\"range\": 5.0, \"rotate\": false}}");
		s.rename("General", "Range", "Place Range");
		assertEquals(JsonParser.parseString("{\"general\": {\"rotate\": false, \"place_range\": 5.0}}"), s.json());
		s.rename("general", "rotate", "face_block");
		assertEquals(new JsonPrimitive(false), s.get("General", "Face Block").orElseThrow());
	}

	@Test
	void moveAcrossGroupsDropsTheEmptyGroup() {
		SavedSettings s = saved("{\"general\": {\"color\": 5}}");
		s.move("General", "Color", "Render", "Fill Color");
		assertEquals(JsonParser.parseString("{\"render\": {\"fill_color\": 5}}"), s.json());
	}

	@Test
	void missingValuesAreLeftAlone() {
		SavedSettings s = saved("{}");
		s.rename("General", "Range", "Place Range");
		s.map("General", "Mode", v -> new JsonPrimitive("x"));
		assertEquals(new JsonObject(), s.json());
	}

	@Test
	void mapRewritesOrRemoves() {
		SavedSettings s = saved("{\"general\": {\"mode\": \"Fast\", \"old\": 1}}");
		s.map("General", "Mode", v -> v.getAsString().equals("Fast") ? new JsonPrimitive("Quick") : v);
		s.map("General", "Old", v -> null);
		assertEquals(JsonParser.parseString("{\"general\": {\"mode\": \"Quick\"}}"), s.json());
	}

	@Test
	void renameGroupMergesWithoutOverwriting() {
		SavedSettings s = saved("{\"misc\": {\"a\": 1, \"b\": 2}, \"other\": {\"b\": 3}}");
		s.renameGroup("Misc", "Other");
		assertEquals(JsonParser.parseString("{\"other\": {\"b\": 3, \"a\": 1}}"), s.json());
	}
}
