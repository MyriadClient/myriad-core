package dev.myriadclient.api.setting;

import com.google.gson.JsonParser;
import dev.myriadclient.api.util.MyriadId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.*;

class NewSettingTypesTest {
	@Test
	void blockPositionsRoundTrip() {
		Settings settings = new Settings();
		BlockPosSetting s = settings.group("General").blockPos("Origin").defaultValue(new BlockPos(1, 2, 3)).build();
		assertEquals("1 2 3", s.valueString());
		assertTrue(s.parse("10, 64 -5"));
		assertEquals(new BlockPos(10, 64, -5), s.get());
		assertFalse(s.parse("10 64"));
		BlockPosSetting copy = new Settings().group("General").blockPos("Origin").build();
		copy.fromJson(s.toJson());
		assertEquals(s.get(), copy.get());
	}

	@Test
	void choicesOnlyAcceptListedNamesButKeepSavedOnes() {
		List<String> files = List.of("castle.litematic", "farm.litematic");
		ChoiceSetting s = new Settings().group("General").choice("Schematic", () -> files).defaultValue("farm.litematic").build();
		assertTrue(s.parse("CASTLE.litematic"));
		assertEquals("castle.litematic", s.get());
		assertFalse(s.parse("missing.litematic"));
		s.fromJson(JsonParser.parseString("\"deleted.litematic\""));
		assertEquals("deleted.litematic", s.get());
	}

	@Test
	void moduleListsRememberIds() {
		ModuleListSetting s = new Settings().group("General").modules("Pause With").defaultValue("myriad-essentials:freecam").build();
		assertEquals(Set.of(MyriadId.parse("myriad-essentials:freecam")), s.get());
		ModuleListSetting copy = new Settings().group("General").modules("Pause With").build();
		copy.fromJson(s.toJson());
		assertEquals(s.get(), copy.get());
	}

	@Test
	void filesExposeTheirPath() {
		FileSetting s = new Settings().group("General").file("Schematic").extensions("litematic").build();
		assertNull(s.path());
		assertFalse(s.exists());
		s.set("/tmp/castle.litematic");
		assertEquals("castle.litematic", s.valueString());
		assertEquals(List.of("litematic"), s.extensions());
	}
}
