package dev.myriadclient.api.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** An ordered list of strings. Command input: {@code add <v>}, {@code remove <v>}, {@code clear}. */
public class StringListSetting extends Setting<List<String>> {
	public StringListSetting(String name, String description, List<String> defaultValue, Supplier<Boolean> visible) {
		super(name, description, defaultValue, visible);
	}

	@Override
	protected List<String> copy(List<String> v) {
		return v == null ? new ArrayList<>() : new ArrayList<>(v);
	}

	public void add(String s) {
		if (!value.contains(s)) {
			value.add(s);
			changed();
		}
	}

	public void remove(String s) {
		if (value.remove(s)) changed();
	}

	@Override
	public JsonElement toJson() {
		JsonArray a = new JsonArray();
		value.forEach(a::add);
		return a;
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json == null || !json.isJsonArray()) return;
		List<String> list = new ArrayList<>();
		for (JsonElement e : json.getAsJsonArray()) if (e.isJsonPrimitive()) list.add(e.getAsString());
		set(list);
	}

	@Override
	public boolean parse(String input) {
		String in = input.trim();
		if (in.equalsIgnoreCase("clear")) {
			set(new ArrayList<>());
			return true;
		}
		if (in.startsWith("add ")) {
			add(in.substring(4).trim());
			return true;
		}
		if (in.startsWith("remove ")) {
			remove(in.substring(7).trim());
			return true;
		}
		return false;
	}

	@Override
	public List<String> suggestions() {
		return List.of("add", "remove", "clear");
	}

	@Override
	public String valueString() {
		return String.join(", ", get());
	}

	public static class Builder extends Setting.Builder<dev.myriadclient.api.setting.StringListSetting.Builder, List<String>, StringListSetting> {
		public Builder(String name) {
			super(name, List.of());
		}

		public dev.myriadclient.api.setting.StringListSetting.Builder defaultValue(String... values) {
			return defaultValue(List.of(values));
		}

		@Override
		protected StringListSetting create() {
			return new StringListSetting(name, description, defaultValue, visible);
		}
	}
}
