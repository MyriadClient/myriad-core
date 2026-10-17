package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.function.Supplier;

/**
 * One of a list of names that's only known at runtime (schematic files, kits, profiles), shown as a dropdown. The
 * value is kept even if it disappears from the list later, so a missing file doesn't silently reset the choice.
 */
public class ChoiceSetting extends Setting<String> {
	private final Supplier<List<String>> options;

	public ChoiceSetting(String name, String description, String defaultValue, Supplier<Boolean> visible, Supplier<List<String>> options) {
		super(name, description, defaultValue, visible);
		this.options = options;
	}

	/** The current choices. */
	public List<String> options() {
		return options.get();
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
		for (String o : options()) {
			if (o.equalsIgnoreCase(input.trim())) {
				set(o);
				return true;
			}
		}
		return false;
	}

	@Override
	public List<String> suggestions() {
		return options();
	}

	public static class Builder extends Setting.Builder<dev.myriadclient.api.setting.ChoiceSetting.Builder, String, ChoiceSetting> {
		private final Supplier<List<String>> options;

		public Builder(String name, Supplier<List<String>> options) {
			super(name, "");
			this.options = options;
		}

		@Override
		protected ChoiceSetting create() {
			return new ChoiceSetting(name, description, defaultValue, visible, options);
		}
	}
}
