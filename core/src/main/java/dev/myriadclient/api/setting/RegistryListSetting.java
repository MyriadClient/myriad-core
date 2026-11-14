package dev.myriadclient.api.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A set of entries from a vanilla registry (blocks, items, entity types, status effects, …). Edited in the UI with
 * an inline searchable picker. Command input: {@code add <id>}, {@code remove <id>}, {@code clear}. Saved by id; ids
 * no longer in the registry are dropped when loading.
 */
public class RegistryListSetting<T> extends Setting<Set<T>> {
	private final Registry<T> registry;
	private final Predicate<T> filter;

	/**
	 * A setting as its {@link Builder} makes it; get one from {@link SettingGroup#registryList} or the shortcuts beside
	 * it, or call this from a subclass.
	 *
	 * @param filter which entries may be picked; null for all
	 */
	public RegistryListSetting(String name, @Nullable String description, Set<T> defaultValue, @Nullable Supplier<Boolean> visible, Registry<T> registry, @Nullable Predicate<T> filter) {
		super(name, description, defaultValue, visible);
		this.registry = registry;
		this.filter = filter == null ? t -> true : filter;
	}

	/** The registry the entries come from. */
	public Registry<T> registry() {
		return registry;
	}

	/** Whether {@code t} may be picked (e.g. only blocks with a collision shape). */
	public boolean accepts(T t) {
		return filter.test(t);
	}

	/** Whether {@code t} is in the set in effect. */
	public boolean contains(T t) {
		return get().contains(t);
	}

	/** Adds {@code t} to the player's set, if the filter {@linkplain #accepts accepts} it. */
	public void add(T t) {
		if (accepts(t) && value.add(t)) changed();
	}

	/** Removes {@code t} from the player's set. */
	public void remove(T t) {
		if (value.remove(t)) changed();
	}

	/** Removes {@code t} if it's in the set, otherwise adds it (if accepted). */
	public void toggle(T t) {
		if (contains(t)) remove(t);
		else add(t);
	}

	@Override
	protected Set<T> copy(Set<T> v) {
		return v == null ? new LinkedHashSet<>() : new LinkedHashSet<>(v);
	}

	@Override
	public JsonElement toJson() {
		JsonArray a = new JsonArray();
		for (T t : value) {
			Identifier id = registry.getKey(t);
			if (id != null) a.add(id.toString());
		}
		return a;
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json == null || !json.isJsonArray()) return;
		Set<T> set = new LinkedHashSet<>();
		for (JsonElement e : json.getAsJsonArray()) {
			Identifier id = e.isJsonPrimitive() ? Identifier.tryParse(e.getAsString()) : null;
			if (id == null) continue;
			registry.getOptional(id).filter(filter).ifPresent(set::add);
		}
		set(set);
	}

	@Override
	public boolean parse(String input) {
		String in = input.trim();
		if (in.equalsIgnoreCase("clear")) {
			set(new LinkedHashSet<>());
			return true;
		}
		String[] parts = in.split("\\s+", 2);
		if (parts.length != 2) return false;
		Identifier id = Identifier.tryParse(parts[1]);
		if (id == null) return false;
		var entry = registry.getOptional(id);
		if (entry.isEmpty()) return false;
		switch (parts[0].toLowerCase()) {
			case "add" -> add(entry.get());
			case "remove" -> remove(entry.get());
			default -> {
				return false;
			}
		}
		return true;
	}

	@Override
	public List<String> suggestions() {
		return List.of("add", "remove", "clear");
	}

	@Override
	public String valueString() {
		List<String> ids = new ArrayList<>();
		for (T t : get()) ids.add(String.valueOf(registry.getKey(t)));
		return get().size() + " selected" + (ids.isEmpty() ? "" : ": " + String.join(", ", ids.subList(0, Math.min(5, ids.size()))) + (ids.size() > 5 ? ", …" : ""));
	}

	/** Builds a {@link RegistryListSetting}; empty by default. */
	public static class Builder<T> extends Setting.Builder<RegistryListSetting.Builder<T>, Set<T>, RegistryListSetting<T>> {
		private final Registry<T> registry;
		private @Nullable Predicate<T> filter;

		/**
		 * A builder for a setting called {@code name}; prefer {@link SettingGroup#registryList}, which adds it to the
		 * group.
		 */
		public Builder(String name, Registry<T> registry) {
			super(name, Set.of());
			this.registry = registry;
		}

		/** The entries it starts with. */
		@SafeVarargs
		public final RegistryListSetting.Builder<T> defaultValue(T... values) {
			return defaultValue(new LinkedHashSet<>(List.of(values)));
		}

		/** Only entries passing {@code filter} can be picked (e.g. only blocks with a collision shape). */
		public RegistryListSetting.Builder<T> filter(Predicate<T> filter) {
			this.filter = filter;
			return this;
		}

		@Override
		protected RegistryListSetting<T> create() {
			return new RegistryListSetting<>(name, description, defaultValue, visible, registry, filter);
		}
	}

	/** A builder for a set of blocks, not added to any group; prefer {@link SettingGroup#blocks(String)}. */
	public static RegistryListSetting.Builder<Block> blocks(String name) {
		return new RegistryListSetting.Builder<>(name, BuiltInRegistries.BLOCK);
	}

	/** A builder for a set of items, not added to any group; prefer {@link SettingGroup#items(String)}. */
	public static RegistryListSetting.Builder<Item> items(String name) {
		return new RegistryListSetting.Builder<>(name, BuiltInRegistries.ITEM);
	}

	/** A builder for a set of entity types, not added to any group; prefer {@link SettingGroup#entityTypes(String)}. */
	public static RegistryListSetting.Builder<EntityType<?>> entityTypes(String name) {
		return new RegistryListSetting.Builder<>(name, BuiltInRegistries.ENTITY_TYPE);
	}

	/**
	 * A builder for a set of status effects, not added to any group; prefer {@link SettingGroup#statusEffects(String)}.
	 */
	public static RegistryListSetting.Builder<MobEffect> statusEffects(String name) {
		return new RegistryListSetting.Builder<>(name, BuiltInRegistries.MOB_EFFECT);
	}
}
