package dev.myriadclient.api.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import org.jspecify.annotations.Nullable;

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

	/**
	 * A setting as its {@link Builder} makes it; get one from {@link SettingGroup#enumSet(String, Class)}, or call this
	 * from a subclass.
	 */
	public EnumSetSetting(String name, @Nullable String description, Class<E> type, Set<E> defaultValue, @Nullable Supplier<Boolean> visible) {
		super(name, description, EnumSet.copyOf(defaultValue.isEmpty() ? EnumSet.noneOf(type) : defaultValue), visible);
		this.type = type;
	}

	/** The enum the constants come from. */
	public Class<E> type() {
		return type;
	}

	/** Every constant of the enum, in declaration order; a new array each call. */
	public E[] values() {
		return type.getEnumConstants();
	}

	/** Whether {@code e} is in the set in effect. */
	public boolean contains(E e) {
		return get().contains(e);
	}

	/** Adds {@code e} to the player's set, or removes it if it's there. */
	public void toggle(E e) {
		if (!value.remove(e)) value.add(e);
		changed();
	}

	/** Adds {@code e} to the player's set. */
	public void add(E e) {
		if (value.add(e)) changed();
	}

	/** Removes {@code e} from the player's set. */
	public void remove(E e) {
		if (value.remove(e)) changed();
	}

	@Override
	protected Set<E> copy(Set<E> v) {
		// Also runs from Setting's constructor, before type is set: the default is an EnumSet already there.
		if (v instanceof EnumSet<E> set) return set.clone();
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

	private @Nullable E find(String s) {
		String in = s.trim();
		for (E e : values()) {
			if (e.name().equalsIgnoreCase(in) || EnumSetting.displayName(e).equalsIgnoreCase(in) || EnumSetting.displayName(e).replace(" ", "").equalsIgnoreCase(in)) return e;
		}
		return null;
	}

	@Override
	public boolean parse(String input) {
		String in = input.trim();
		// "none" is how an empty set shows.
		if (in.equalsIgnoreCase("clear") || in.equalsIgnoreCase("none")) {
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

	/** Builds an {@link EnumSetSetting}; empty by default. */
	public static class Builder<E extends Enum<E>> extends Setting.Builder<EnumSetSetting.Builder<E>, Set<E>, EnumSetSetting<E>> {
		private final Class<E> type;

		/**
		 * A builder for a setting called {@code name}; prefer {@link SettingGroup#enumSet(String, Class)}, which adds
		 * it to the group.
		 */
		public Builder(String name, Class<E> type) {
			super(name, EnumSet.noneOf(type));
			this.type = type;
		}

		/** The constants it starts with. */
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
