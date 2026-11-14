package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * One of an enum's constants. Displayed with {@code toString()} if the enum overrides it, otherwise the constant
 * name in Title Case ({@code FAST_PLACE} as "Fast Place"). Typed by constant name or display name, in any case and with
 * or without spaces.
 */
public class EnumSetting<E extends Enum<E>> extends Setting<E> {
	private final E[] values;

	/**
	 * A setting as its {@link Builder} makes it; get one from {@link SettingGroup#oneOf(String, Enum)}, or call this
	 * from a subclass.
	 */
	public EnumSetting(String name, @Nullable String description, E defaultValue, @Nullable Supplier<Boolean> visible) {
		super(name, description, defaultValue, visible);
		this.values = defaultValue.getDeclaringClass().getEnumConstants();
	}

	/** Every constant of the enum, in declaration order (the array is shared: don't change it). */
	public E[] values() {
		return values;
	}

	/** Sets the player's value {@code dir} constants on from it (negative goes back), wrapping around at the ends. */
	public void cycle(int dir) {
		int i = Math.floorMod(value.ordinal() + dir, values.length);
		set(values[i]);
	}

	/** {@code e} for people: its {@code toString()} if the enum overrides it, otherwise its name in Title Case. */
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

	/** Builds an {@link EnumSetting}, starting at the default given to the constructor. */
	public static class Builder<E extends Enum<E>> extends Setting.Builder<EnumSetting.Builder<E>, E, EnumSetting<E>> {
		/**
		 * A builder for a setting called {@code name}; prefer {@link SettingGroup#oneOf(String, Enum)}, which adds it
		 * to the group.
		 */
		public Builder(String name, E defaultValue) {
			super(name, defaultValue);
		}

		@Override
		protected EnumSetting<E> create() {
			return new EnumSetting<>(name, description, defaultValue, visible);
		}
	}
}
