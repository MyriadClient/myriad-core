package dev.myriadclient.api.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Any number of an enum's constants ("which of these"): sound kinds to block, what to notify about, which parts to
 * draw. Shown as a picker; command input: {@code add <v>}, {@code remove <v>}, {@code clear}, or a comma-separated
 * list to replace the lot.
 */
public class EnumSetSetting<E extends Enum<E>> extends Setting<Set<E>> {
	private final Class<E> type;

	public EnumSetSetting(String name, String description, Class<E> type, Set<E> defaultValue, Supplier<Boolean> visible) {
		super(name, description, EnumSet.copyOf(defaultValue.isEmpty() ? EnumSet.noneOf(type) : defaultValue), visible);
		this.type = type;
	}

	public Class<E> type() {
		return type;
	}

	public E[] values() {
		return type.getEnumConstants();
	}

	public boolean contains(E e) {
		return get().contains(e);
	}

	public void toggle(E e) {
		if (!value.remove(e)) value.add(e);
		changed();
	}

	public void add(E e) {
		if (value.add(e)) changed();
	}

	public void remove(E e) {
		if (value.remove(e)) changed();
	}

	@Override
	protected Set<E> copy(Set<E> v) {
		return v == null || v.isEmpty() ? EnumSet.noneOf(type) : EnumSet.copyOf(v);
	}

	@Override
	public JsonElement toJson() {
		JsonArray a = new JsonArray();
		for (E e : value) a.add(e.name());
		return a;
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json == null || !json.isJsonArray()) return;
		Set<E> set = EnumSet.noneOf(type);
		for (JsonElement e : json.getAsJsonArray()) {
			E found = e.isJsonPrimitive() ? find(e.getAsString()) : null;
			if (found != null) set.add(found);
		}
		set(set);
	}

	private E find(String s) {
		String in = s.trim();
		for (E e : values()) {
			if (e.name().equalsIgnoreCase(in) || EnumSetting.displayName(e).equalsIgnoreCase(in) || EnumSetting.displayName(e).replace(" ", "").equalsIgnoreCase(in)) return e;
		}
		return null;
	}

	@Override
	public boolean parse(String input) {
		String in = input.trim();
		if (in.equalsIgnoreCase("clear")) {
			set(EnumSet.noneOf(type));
			return true;
		}
		if (in.toLowerCase(Locale.ROOT).startsWith("add ") || in.toLowerCase(Locale.ROOT).startsWith("remove ")) {
			boolean add = in.toLowerCase(Locale.ROOT).startsWith("add ");
			E e = find(in.substring(add ? 4 : 7));
			if (e == null) return false;
			if (add) add(e);
			else remove(e);
			return true;
		}
		Set<E> set = EnumSet.noneOf(type);
		for (String part : in.split(",")) {
			if (part.isBlank()) continue;
			E e = find(part);
			if (e == null) return false;
			set.add(e);
		}
		set(set);
		return true;
	}

	@Override
	public List<String> suggestions() {
		List<String> out = new ArrayList<>(List.of("clear"));
		for (E e : values()) out.add(EnumSetting.displayName(e).replace(" ", ""));
		return out;
	}

	@Override
	public String valueString() {
		if (get().isEmpty()) return "none";
		return String.join(", ", get().stream().map(EnumSetting::displayName).toList());
	}

	public static class Builder<E extends Enum<E>> extends Setting.Builder<EnumSetSetting.Builder<E>, Set<E>, EnumSetSetting<E>> {
		private final Class<E> type;

		public Builder(String name, Class<E> type) {
			super(name, EnumSet.noneOf(type));
			this.type = type;
		}

		@SafeVarargs
		public final Builder<E> defaultValue(E... values) {
			this.defaultValue = values.length == 0 ? EnumSet.noneOf(type) : EnumSet.copyOf(Arrays.asList(values));
			return this;
		}

		@Override
		protected EnumSetSetting<E> create() {
			return new EnumSetSetting<>(name, description, type, defaultValue, visible);
		}
	}
}
