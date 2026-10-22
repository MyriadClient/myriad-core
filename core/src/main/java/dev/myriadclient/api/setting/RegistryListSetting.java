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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A set of entries from a vanilla registry (blocks, items, entity types, status effects, …). Edited in the UI with
 * an inline searchable picker. Command input: {@code add <id>}, {@code remove <id>}, {@code clear}.
 */
public class RegistryListSetting<T> extends Setting<Set<T>> {
	private final Registry<T> registry;
	private final Predicate<T> filter;

	public RegistryListSetting(String name, String description, Set<T> defaultValue, Supplier<Boolean> visible, Registry<T> registry, Predicate<T> filter) {
		super(name, description, defaultValue, visible);
		this.registry = registry;
		this.filter = filter == null ? t -> true : filter;
	}

	public Registry<T> registry() {
		return registry;
	}

	/** Whether {@code t} may be picked (e.g. only blocks with a collision shape). */
	public boolean accepts(T t) {
		return filter.test(t);
	}

	public boolean contains(T t) {
		return get().contains(t);
	}

	public void add(T t) {
		if (accepts(t) && value.add(t)) changed();
	}

	public void remove(T t) {
		if (value.remove(t)) changed();
	}

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

	public static class Builder<T> extends Setting.Builder<RegistryListSetting.Builder<T>, Set<T>, RegistryListSetting<T>> {
		private final Registry<T> registry;
		private Predicate<T> filter;

		public Builder(String name, Registry<T> registry) {
			super(name, Set.of());
			this.registry = registry;
		}

		@SafeVarargs
		public final RegistryListSetting.Builder<T> defaultValue(T... values) {
			return defaultValue(new LinkedHashSet<>(List.of(values)));
		}

		public RegistryListSetting.Builder<T> filter(Predicate<T> filter) {
			this.filter = filter;
			return this;
		}

		@Override
		protected RegistryListSetting<T> create() {
			return new RegistryListSetting<>(name, description, defaultValue, visible, registry, filter);
		}
	}

	public static RegistryListSetting.Builder<Block> blocks(String name) {
		return new RegistryListSetting.Builder<>(name, BuiltInRegistries.BLOCK);
	}

	public static RegistryListSetting.Builder<Item> items(String name) {
		return new RegistryListSetting.Builder<>(name, BuiltInRegistries.ITEM);
	}

	public static RegistryListSetting.Builder<EntityType<?>> entityTypes(String name) {
		return new RegistryListSetting.Builder<>(name, BuiltInRegistries.ENTITY_TYPE);
	}

	public static RegistryListSetting.Builder<MobEffect> statusEffects(String name) {
		return new RegistryListSetting.Builder<>(name, BuiltInRegistries.MOB_EFFECT);
	}
}
