package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * On or off, shown as a checkbox. Typed as {@code true}/{@code on}/{@code yes}/{@code 1}, {@code false}/{@code off}/
 * {@code no}/{@code 0}, or {@code toggle}.
 */
public class BoolSetting extends Setting<Boolean> {
	/**
	 * A setting as its {@link Builder} makes it; get one from {@link SettingGroup#bool(String)}, or call this from a
	 * subclass.
	 */
	public BoolSetting(String name, @Nullable String description, boolean defaultValue, @Nullable Supplier<Boolean> visible) {
		super(name, description, defaultValue, visible);
	}

	/**
	 * Flips the value you see, as a click on it would: the override in effect if it takes edits (a control panel), the
	 * value in effect otherwise. Under an override that takes no edits it flips the player's own value, which shows once
	 * the override is taken off.
	 */
	public void toggle() {
		if (editsTakeEffect()) edit(!get());
		else set(!playerValue());
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

	/** Builds a {@link BoolSetting}; off by default. */
	public static class Builder extends Setting.Builder<BoolSetting.Builder, Boolean, BoolSetting> {
		/**
		 * A builder for a setting called {@code name}; prefer {@link SettingGroup#bool(String)}, which adds it to the
		 * group.
		 */
		public Builder(String name) {
			super(name, false);
		}

		@Override
		protected BoolSetting create() {
			return new BoolSetting(name, description, defaultValue, visible);
		}
	}
}
