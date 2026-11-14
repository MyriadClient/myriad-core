package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * One entry of a game registry: a single item (what to place), block (what to mine), and so on. Typed and saved by id
 * ({@code obsidian}, {@code minecraft:obsidian}); entries the builder's filter rejects can't be picked.
 */
public class RegistrySetting<T> extends Setting<T> {
	private final Registry<T> registry;
	private final Predicate<T> filter;

	/**
	 * A setting as its {@link Builder} makes it; get one from {@link SettingGroup#item}, {@link SettingGroup#block},
	 * {@link SettingGroup#sound} or {@link SettingGroup#registry}, or call this from a subclass.
	 *
	 * @param filter which entries may be picked; null for all
	 */
	public RegistrySetting(String name, @Nullable String description, T defaultValue, @Nullable Supplier<Boolean> visible, Registry<T> registry, @Nullable Predicate<T> filter) {
		super(name, description, defaultValue, visible);
		this.registry = registry;
		this.filter = filter == null ? t -> true : filter;
	}

	/** The registry the entry comes from. */
	public Registry<T> registry() {
		return registry;
	}

	/** Whether {@code t} may be picked: it passes the builder's filter. */
	public boolean accepts(T t) {
		return filter.test(t);
	}

	@Override
	protected T validate(@Nullable T v) {
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

	/** Builds a {@link RegistrySetting}, starting at the default given to the constructor. */
	public static class Builder<T> extends Setting.Builder<RegistrySetting.Builder<T>, T, RegistrySetting<T>> {
		private final Registry<T> registry;
		private @Nullable Predicate<T> filter;

		/**
		 * A builder for a setting called {@code name}; prefer {@link SettingGroup#registry}, which adds it to the
		 * group.
		 */
		public Builder(String name, Registry<T> registry, T defaultValue) {
			super(name, defaultValue);
			this.registry = registry;
		}

		/** Only entries passing {@code filter} can be picked. */
		public RegistrySetting.Builder<T> filter(Predicate<T> filter) {
			this.filter = filter;
			return this;
		}

		@Override
		protected RegistrySetting<T> create() {
			return new RegistrySetting<>(name, description, defaultValue, visible, registry, filter);
		}
	}

	static RegistrySetting.Builder<Item> item(String name, Item defaultValue) {
		return new RegistrySetting.Builder<>(name, BuiltInRegistries.ITEM, defaultValue);
	}

	static RegistrySetting.Builder<Block> block(String name, Block defaultValue) {
		return new RegistrySetting.Builder<>(name, BuiltInRegistries.BLOCK, defaultValue);
	}
}
