package dev.myriadclient.api.setting;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Overrides lie over the player's value, the top layer wins, and only the player's value is ever saved. */
class SettingLayersTest {
	static class Mod {
		final Settings settings = new Settings();
		final SettingGroup general = settings.group("General");
		final IntSetting width = general.integer("Width").defaultValue(1).range(1, 5).build();
	}

	@Test
	void anOverrideWinsAndReleasingItUncoversThePlayersValue() {
		Mod m = new Mod();
		m.width.set(2);
		Object panel = new Object();
		m.width.override(panel, Setting.Layer.CONTROL_PANEL, "Digging", 3);
		assertEquals(3, m.width.get());
		assertEquals(2, m.width.playerValue());
		assertEquals("Digging", m.width.overriddenBy());
		m.width.release(panel);
		assertEquals(2, m.width.get());
		assertNull(m.width.overriddenBy());
	}

	@Test
	void aHigherLayerWinsWhateverTheOrder() {
		Mod m = new Mod();
		Object module = new Object(), panel = new Object();
		m.width.override(module, Setting.Layer.MODULE, "Recast", 5);
		m.width.override(panel, Setting.Layer.CONTROL_PANEL, "Hunting", 3);
		assertEquals(5, m.width.get());
		m.width.release(module);
		assertEquals(3, m.width.get());
	}

	@Test
	void onlyThePlayersValueIsSaved() {
		Mod m = new Mod();
		m.width.set(2);
		m.width.override(new Object(), Setting.Layer.CONTROL_PANEL, "Digging", 4);
		assertEquals(JsonParser.parseString("{\"general\":{\"width\":2}}"), m.settings.toJson());
	}

	@Test
	void editsGoToTheOverrideThatTakesThem() {
		Mod m = new Mod();
		List<Integer> saved = new ArrayList<>();
		m.width.override(new Object(), Setting.Layer.CONTROL_PANEL, "Digging", 3, saved::add);
		m.width.edit(4);
		assertEquals(4, m.width.get());
		assertEquals(1, m.width.playerValue());
		assertEquals(List.of(4), saved);
		// Without an editor, an edit is the player's.
		Mod n = new Mod();
		n.width.override(new Object(), Setting.Layer.MODULE, "Recast", 3);
		n.width.edit(2);
		assertEquals(3, n.width.get());
		assertEquals(2, n.width.playerValue());
	}

	@Test
	void listenersHearTheValueInEffect() {
		Mod m = new Mod();
		List<Integer> heard = new ArrayList<>();
		m.width.onChanged(heard::add);
		Object panel = new Object();
		m.width.override(panel, Setting.Layer.CONTROL_PANEL, "Digging", 3);
		m.width.set(2);
		m.width.release(panel);
		// The override, then the player's edit (heard as the value still in effect), then the player's value again.
		assertEquals(List.of(3, 3, 2), heard);
	}

	@Test
	void decodingAndEncodingLeaveTheSettingAlone() {
		Mod m = new Mod();
		List<Integer> heard = new ArrayList<>();
		m.width.onChanged(heard::add);
		assertEquals(4, m.width.decode("4"));
		assertNull(m.width.decode("nope"));
		assertEquals(5, m.width.decode(JsonParser.parseString("5")));
		assertEquals(JsonParser.parseString("3"), m.width.encode(3));
		assertEquals(1, m.width.get());
		assertTrue(heard.isEmpty());
		assertFalse(m.width.isOverridden());
	}
}
