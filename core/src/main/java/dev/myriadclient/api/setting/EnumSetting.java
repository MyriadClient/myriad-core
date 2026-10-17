package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * One of an enum's constants. Displayed with {@code toString()} if the enum overrides it, otherwise the constant
 * name in Title Case.
 */
public class EnumSetting<E extends Enum<E>> extends Setting<E> {
	private final E[] values;

	public EnumSetting(String name, String description, E defaultValue, Supplier<Boolean> visible) {
		super(name, description, defaultValue, visible);
		this.values = defaultValue.getDeclaringClass().getEnumConstants();
	}

	public E[] values() {
		return values;
	}

	public void cycle(int dir) {
		int i = Math.floorMod(value.ordinal() + dir, values.length);
		set(values[i]);
	}

	public static String displayName(Enum<?> e) {
		String s = e.toString();
		if (!s.equals(e.name())) return s;
		StringBuilder sb = new StringBuilder();
		for (String part : e.name().split("_")) {
			if (part.isEmpty()) continue;
			if (!sb.isEmpty()) sb.append(' ');
			sb.append(part.charAt(0)).append(part.substring(1).toLowerCase(Locale.ROOT));
		}
		return sb.toString();
	}

	@Override
	public String valueString() {
		return displayName(get());
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value.name());
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json != null && json.isJsonPrimitive()) parse(json.getAsString());
	}

	@Override
	public boolean parse(String input) {
		String in = input.trim();
		for (E e : values) {
			if (e.name().equalsIgnoreCase(in) || displayName(e).equalsIgnoreCase(in) || displayName(e).replace(" ", "").equalsIgnoreCase(in)) {
				set(e);
				return true;
			}
		}
		return false;
	}

	@Override
	public List<String> suggestions() {
		return Arrays.stream(values).map(e -> displayName(e).replace(" ", "")).toList();
	}

	public static class Builder<E extends Enum<E>> extends Setting.Builder<dev.myriadclient.api.setting.EnumSetting.Builder<E>, E, EnumSetting<E>> {
		public Builder(String name, E defaultValue) {
			super(name, defaultValue);
		}

		@Override
		protected EnumSetting<E> create() {
			return new EnumSetting<>(name, description, defaultValue, visible);
		}
	}
}
