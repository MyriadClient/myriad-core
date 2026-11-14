package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/** Free text, shown as a text box; anything typed is accepted as it is. */
public class StringSetting extends Setting<String> {
	/**
	 * A setting as its {@link Builder} makes it; get one from {@link SettingGroup#string(String)}, or call this from a
	 * subclass.
	 */
	public StringSetting(String name, @Nullable String description, String defaultValue, @Nullable Supplier<Boolean> visible) {
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

	/** Builds a {@link StringSetting}; empty by default. */
	public static class Builder extends Setting.Builder<StringSetting.Builder, String, StringSetting> {
		/**
		 * A builder for a setting called {@code name}; prefer {@link SettingGroup#string(String)}, which adds it to the
		 * group.
		 */
		public Builder(String name) {
			super(name, "");
		}

		@Override
		protected StringSetting create() {
			return new StringSetting(name, description, defaultValue, visible);
		}
	}
}
