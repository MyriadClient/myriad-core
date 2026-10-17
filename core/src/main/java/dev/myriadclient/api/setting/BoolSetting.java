package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.function.Supplier;

public class BoolSetting extends Setting<Boolean> {
	public BoolSetting(String name, String description, boolean defaultValue, Supplier<Boolean> visible) {
		super(name, description, defaultValue, visible);
	}

	public void toggle() {
		set(!get());
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value);
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json != null && json.isJsonPrimitive()) set(json.getAsBoolean());
	}

	@Override
	public boolean parse(String input) {
		switch (input.toLowerCase()) {
			case "true", "on", "yes", "1" -> set(true);
			case "false", "off", "no", "0" -> set(false);
			case "toggle" -> toggle();
			default -> {
				return false;
			}
		}
		return true;
	}

	@Override
	public List<String> suggestions() {
		return List.of("true", "false", "toggle");
	}

	public static class Builder extends Setting.Builder<dev.myriadclient.api.setting.BoolSetting.Builder, Boolean, BoolSetting> {
		public Builder(String name) {
			super(name, false);
		}

		@Override
		protected BoolSetting create() {
			return new BoolSetting(name, description, defaultValue, visible);
		}
	}
}
