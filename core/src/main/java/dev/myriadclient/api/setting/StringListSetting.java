package dev.myriadclient.api.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * An ordered list of strings, without duplicates when added one at a time. Command input: {@code add <v>},
 * {@code remove <v>}, {@code clear}, or a comma-separated list to replace the lot.
 */
public class StringListSetting extends Setting<List<String>> {
	/**
	 * A setting as its {@link Builder} makes it; get one from {@link SettingGroup#stringList(String)}, or call this
	 * from a subclass.
	 */
	public StringListSetting(String name, @Nullable String description, List<String> defaultValue, @Nullable Supplier<Boolean> visible) {
		super(name, description, defaultValue, visible);
	}

	@Override
	protected List<String> copy(List<String> v) {
		return v == null ? new ArrayList<>() : new ArrayList<>(v);
	}

	/** Adds {@code s} to the end of the player's list, unless it's already there. */
	public void add(String s) {
		if (!value.contains(s)) {
			value.add(s);
			changed();
		}
	}

	/** Removes {@code s} from the player's list, if it's there. */
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
		// The whole list, as it shows: "2b2t.org, 6b6t.org".
		List<String> list = new ArrayList<>();
		for (String part : in.split(",")) if (!part.isBlank()) list.add(part.trim());
		if (list.isEmpty()) return false;
		set(list);
		return true;
	}

	@Override
	public List<String> suggestions() {
		return List.of("add", "remove", "clear");
	}

	@Override
	public String valueString() {
		return String.join(", ", get());
	}

	/** Builds a {@link StringListSetting}; empty by default. */
	public static class Builder extends Setting.Builder<StringListSetting.Builder, List<String>, StringListSetting> {
		/**
		 * A builder for a setting called {@code name}; prefer {@link SettingGroup#stringList(String)}, which adds it to
		 * the group.
		 */
		public Builder(String name) {
			super(name, List.of());
		}

		/** The entries it starts with, in order. */
		public StringListSetting.Builder defaultValue(String... values) {
			return defaultValue(List.of(values));
		}

		@Override
		protected StringListSetting create() {
			return new StringListSetting(name, description, defaultValue, visible);
		}
	}
}
