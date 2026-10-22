package dev.myriadclient.impl.share;

import com.google.gson.JsonObject;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.setting.Settings;
import dev.myriadclient.api.setting.StringSetting;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShareableSettingsTest {
	private final Settings settings = new Settings();
	private final SettingGroup general = settings.group("General");
	private final IntSetting range = general.integer("Range").defaultValue(4).range(1, 10).build();
	private final IntSetting delay = general.integer("Delay").defaultValue(2).range(0, 10).build();
	private final StringSetting webhook = general.string("Webhook").protect().build();
	private final SettingGroup module = settings.group("Module");
	private final BoolSetting hold = module.bool("Hold").build();

	@Test
	void sharesChangedValuesButNeverProtectedOnes() {
		range.set(7);
		webhook.set("https://discord.example/secret");
		hold.set(true);
		JsonObject shared = ShareManager.shareable(settings, false);
		assertEquals(7, shared.getAsJsonObject("general").get("range").getAsInt());
		assertFalse(shared.getAsJsonObject("general").has("delay"), "defaults stay out");
		assertFalse(shared.toString().contains("secret"), "protected values never go in");
		assertFalse(shared.has("module"), "binds only when asked");
		assertTrue(ShareManager.shareable(settings, true).getAsJsonObject("module").get("hold").getAsBoolean());
	}

	@Test
	void protectedIsMarkedOnTheSetting() {
		assertTrue(webhook.isProtected());
		assertFalse(range.isProtected());
		JsonObject row = ShareView.setting(webhook);
		assertTrue(row.get("protected").getAsBoolean());
		assertFalse(row.has("value") || row.has("shown"));
	}
}
