package dev.myriadclient.api.setting;

import dev.myriadclient.api.util.MyriadId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * A named, collapsible section of settings. The factory methods return builders that add the built setting to this
 * group:
 *
 * <pre>{@code
 * private final DoubleSetting range = sgGeneral.doubleSetting("Range").defaultValue(4.5).range(0, 6).build();
 * }</pre>
 */
public class SettingGroup {
	private final Settings owner;
	private final String name;
	private final String id;
	private final List<Setting<?>> settings = new ArrayList<>();
	private boolean expanded = true;

	SettingGroup(Settings owner, String name) {
		this.owner = owner;
		this.name = name;
		this.id = MyriadId.toPath(name);
	}

	public String name() {
		return name;
	}

	Settings owner() {
		return owner;
	}

	public String id() {
		return id;
	}

	public List<Setting<?>> settings() {
		return Collections.unmodifiableList(settings);
	}

	public boolean isExpanded() {
		return expanded;
	}

	public void setExpanded(boolean expanded) {
		this.expanded = expanded;
	}

	/** Adds a pre-built setting (for custom setting types). */
	public <S extends Setting<?>> S add(S setting) {
		owner.checkUnique(this, setting);
		settings.add(setting);
		setting.setGroup(this);
		return setting;
	}

	private <B extends Setting.Builder<?, ?, ?>> B bind(B builder) {
		builder.group = this;
		return builder;
	}

	public BoolSetting.Builder bool(String name) {
		return bind(new BoolSetting.Builder(name));
	}

	public IntSetting.Builder intSetting(String name) {
		return bind(new IntSetting.Builder(name));
	}

	public DoubleSetting.Builder doubleSetting(String name) {
		return bind(new DoubleSetting.Builder(name));
	}

	public <E extends Enum<E>> EnumSetting.Builder<E> enumSetting(String name, E defaultValue) {
		return bind(new EnumSetting.Builder<>(name, defaultValue));
	}

	public StringSetting.Builder string(String name) {
		return bind(new StringSetting.Builder(name));
	}

	/** Any number of an enum's constants ("which of these"). */
	public <E extends Enum<E>> EnumSetSetting.Builder<E> enumSet(String name, Class<E> type) {
		return bind(new EnumSetSetting.Builder<>(name, type));
	}

	/** A list of whole numbers (slots, ids, levels). */
	public IntListSetting.Builder intList(String name) {
		return bind(new IntListSetting.Builder(name));
	}

	/** A sound, e.g. what to play on an alert. */
	public RegistrySetting.Builder<SoundEvent> sound(String name, SoundEvent defaultValue) {
		return bind(new RegistrySetting.Builder<>(name, BuiltInRegistries.SOUND_EVENT, defaultValue));
	}

	public StringListSetting.Builder stringList(String name) {
		return bind(new StringListSetting.Builder(name));
	}

	public ColorSetting.Builder color(String name) {
		return bind(new ColorSetting.Builder(name));
	}

	public KeybindSetting.Builder keybind(String name) {
		return bind(new KeybindSetting.Builder(name));
	}

	public ActionSetting.Builder action(String name, Runnable action) {
		return bind(new ActionSetting.Builder(name, action));
	}

	/** A single item, e.g. what to place or hold. */
	public RegistrySetting.Builder<Item> item(String name, Item defaultValue) {
		return bind(RegistrySetting.item(name, defaultValue));
	}

	/** A single block, e.g. what to build with. */
	public RegistrySetting.Builder<Block> block(String name, Block defaultValue) {
		return bind(RegistrySetting.block(name, defaultValue));
	}

	/** A single entry of any registry. */
	public <T> RegistrySetting.Builder<T> registry(String name, Registry<T> registry, T defaultValue) {
		return bind(new RegistrySetting.Builder<>(name, registry, defaultValue));
	}

	public BlockPosSetting.Builder blockPos(String name) {
		return bind(new BlockPosSetting.Builder(name));
	}

	/** One of a list of names supplied at runtime (files, kits), shown as a dropdown. */
	public ChoiceSetting.Builder choice(String name, Supplier<List<String>> options) {
		return bind(new ChoiceSetting.Builder(name, options));
	}

	public ModuleListSetting.Builder modules(String name) {
		return bind(new ModuleListSetting.Builder(name));
	}

	public FileSetting.Builder file(String name) {
		return bind(new FileSetting.Builder(name));
	}

	public <T> RegistryListSetting.Builder<T> registryList(String name, Registry<T> registry) {
		return bind(new RegistryListSetting.Builder<>(name, registry));
	}

	public RegistryListSetting.Builder<Block> blocks(String name) {
		return bind(RegistryListSetting.blocks(name));
	}

	public RegistryListSetting.Builder<Item> items(String name) {
		return bind(RegistryListSetting.items(name));
	}

	public RegistryListSetting.Builder<EntityType<?>> entityTypes(String name) {
		return bind(RegistryListSetting.entityTypes(name));
	}

	public RegistryListSetting.Builder<MobEffect> statusEffects(String name) {
		return bind(RegistryListSetting.statusEffects(name));
	}
}
