package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.function.Supplier;

public class StringSetting extends Setting<String> {
	public StringSetting(String name, String description, String defaultValue, Supplier<Boolean> visible) {
		super(name, description, defaultValue, visible);
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value);
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json != null && json.isJsonPrimitive()) set(json.getAsString());
	}

	@Override
	public boolean parse(String input) {
		set(input);
		return true;
	}

	public static class Builder extends Setting.Builder<dev.myriadclient.api.setting.StringSetting.Builder, String, StringSetting> {
		public Builder(String name) {
			super(name, "");
		}

		@Override
		protected StringSetting create() {
			return new StringSetting(name, description, defaultValue, visible);
		}
	}
}
