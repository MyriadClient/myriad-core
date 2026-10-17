package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/** One entry of a game registry: a single item (what to place), block (what to mine), and so on. */
public class RegistrySetting<T> extends Setting<T> {
	private final Registry<T> registry;
	private final Predicate<T> filter;

	public RegistrySetting(String name, String description, T defaultValue, Supplier<Boolean> visible, Registry<T> registry, Predicate<T> filter) {
		super(name, description, defaultValue, visible);
		this.registry = registry;
		this.filter = filter == null ? t -> true : filter;
	}

	public Registry<T> registry() {
		return registry;
	}

	public boolean accepts(T t) {
		return filter.test(t);
	}

	@Override
	protected T validate(T v) {
		return v == null || !accepts(v) ? value == null ? defaultValue : value : v;
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(String.valueOf(registry.getKey(value)));
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json != null && json.isJsonPrimitive()) parse(json.getAsString());
	}

	@Override
	public boolean parse(String input) {
		Identifier id = Identifier.tryParse(input.trim());
		if (id == null || !registry.containsKey(id)) return false;
		T t = registry.getValue(id);
		if (!accepts(t)) return false;
		set(t);
		return true;
	}

	@Override
	public List<String> suggestions() {
		return registry.keySet().stream().filter(id -> accepts(registry.getValue(id))).map(id -> id.getNamespace().equals("minecraft") ? id.getPath() : id.toString()).toList();
	}

	@Override
	public String valueString() {
		Identifier id = registry.getKey(get());
		return id == null ? "" : id.getPath();
	}

	public static class Builder<T> extends Setting.Builder<dev.myriadclient.api.setting.RegistrySetting.Builder<T>, T, RegistrySetting<T>> {
		private final Registry<T> registry;
		private Predicate<T> filter;

		public Builder(String name, Registry<T> registry, T defaultValue) {
			super(name, defaultValue);
			this.registry = registry;
		}

		/** Only entries passing {@code filter} can be picked. */
		public dev.myriadclient.api.setting.RegistrySetting.Builder<T> filter(Predicate<T> filter) {
			this.filter = filter;
			return this;
		}

		@Override
		protected RegistrySetting<T> create() {
			return new RegistrySetting<>(name, description, defaultValue, visible, registry, filter);
		}
	}

	static dev.myriadclient.api.setting.RegistrySetting.Builder<Item> item(String name, Item defaultValue) {
		return new dev.myriadclient.api.setting.RegistrySetting.Builder<>(name, BuiltInRegistries.ITEM, defaultValue);
	}

	static dev.myriadclient.api.setting.RegistrySetting.Builder<Block> block(String name, Block defaultValue) {
		return new dev.myriadclient.api.setting.RegistrySetting.Builder<>(name, BuiltInRegistries.BLOCK, defaultValue);
	}
}
