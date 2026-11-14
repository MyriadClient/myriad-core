package dev.myriadclient.api.setting;

import dev.myriadclient.api.registry.MyriadId;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * A named, collapsible section of settings, from {@link Settings#group(String)}. The factory methods return builders
 * that add the built setting to this group; setting names must be unique within it (the same name twice throws
 * {@link IllegalArgumentException} on {@code build()}).
 *
 * <pre>{@code
 * private final SettingGroup sgTargets = settings.group("Targets");
 * private final DoubleSetting range = sgGeneral.decimal("Range").defaultValue(4.5).range(0, 6).build();
 * private final BoolSetting players = sgTargets.bool("Players").defaultValue(true).build();
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

	/** The heading shown in the settings window. */
	public String name() {
		return name;
	}

	Settings owner() {
		return owner;
	}

	/** {@link #name()} in snake_case: the key the group's settings are saved under. */
	public String id() {
		return id;
	}

	/** The group's settings in the order they were added; unmodifiable, and follows later additions. */
	public List<Setting<?>> settings() {
		return Collections.unmodifiableList(settings);
	}

	/** Whether the group shows open in the settings window: the first group of a set starts open, the rest folded. */
	public boolean isExpanded() {
		return expanded;
	}

	/** Opens or folds the group in the settings window; not saved. */
	public void setExpanded(boolean expanded) {
		this.expanded = expanded;
	}

	/**
	 * Adds a pre-built setting (for custom setting types). Returns it.
	 *
	 * @throws IllegalArgumentException if the group already has a setting with its id
	 */
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

	/** On or off: a checkbox. Off by default. */
	public BoolSetting.Builder bool(String name) {
		return bind(new BoolSetting.Builder(name));
	}

	/** A whole number: a slider over {@code range}, or a box to type in ({@code typed()}). */
	public IntSetting.Builder integer(String name) {
		return bind(new IntSetting.Builder(name));
	}

	/** A number with decimals: a slider over {@code range}, or a box to type in ({@code typed()}). */
	public DoubleSetting.Builder decimal(String name) {
		return bind(new DoubleSetting.Builder(name));
	}

	/** One of an enum's constants ("which one"), starting at {@code defaultValue}. */
	public <E extends Enum<E>> EnumSetting.Builder<E> oneOf(String name, E defaultValue) {
		return bind(new EnumSetting.Builder<>(name, defaultValue));
	}

	/** Free text: a text box. Empty by default. */
	public StringSetting.Builder string(String name) {
		return bind(new StringSetting.Builder(name));
	}

	/** An icon picked from {@link dev.myriadclient.api.ui.Icons}; empty for the default. */
	public IconSetting.Builder icon(String name) {
		return bind(new IconSetting.Builder(name));
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

	/** An ordered list of text entries (server names, chat filters). Empty by default. */
	public StringListSetting.Builder stringList(String name) {
		return bind(new StringListSetting.Builder(name));
	}

	/** A colour, a theme role or a rainbow, with a picker. Opaque white by default. */
	public ColorSetting.Builder color(String name) {
		return bind(new ColorSetting.Builder(name));
	}

	/** A key or mouse button, for an action inside a module. Unbound by default. */
	public KeybindSetting.Builder keybind(String name) {
		return bind(new KeybindSetting.Builder(name));
	}

	/** A button that runs {@code action} when clicked (or set from a command); not saved. */
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

	/** A block position, with "set to where I'm standing". 0, 0, 0 by default; protected from shares by default. */
	public BlockPosSetting.Builder blockPos(String name) {
		return bind(new BlockPosSetting.Builder(name));
	}

	/** One of a list of names supplied at runtime (files, kits), shown as a dropdown. */
	public ChoiceSetting.Builder choice(String name, Supplier<List<String>> options) {
		return bind(new ChoiceSetting.Builder(name, options));
	}

	/** A set of modules, kept by id. None by default. */
	public ModuleListSetting.Builder modules(String name) {
		return bind(new ModuleListSetting.Builder(name));
	}

	/** A file on disk, from the system's file picker. None by default; protected from shares by default. */
	public FileSetting.Builder file(String name) {
		return bind(new FileSetting.Builder(name));
	}

	/** A set of entries of any game registry. None by default. */
	public <T> RegistryListSetting.Builder<T> registryList(String name, Registry<T> registry) {
		return bind(new RegistryListSetting.Builder<>(name, registry));
	}

	/** A set of blocks (what to mine, what to highlight). None by default. */
	public RegistryListSetting.Builder<Block> blocks(String name) {
		return bind(RegistryListSetting.blocks(name));
	}

	/** A set of items (what to keep, what to throw). None by default. */
	public RegistryListSetting.Builder<Item> items(String name) {
		return bind(RegistryListSetting.items(name));
	}

	/** A set of entity types (what to target, what to draw). None by default. */
	public RegistryListSetting.Builder<EntityType<?>> entityTypes(String name) {
		return bind(RegistryListSetting.entityTypes(name));
	}

	/** A set of status effects. None by default. */
	public RegistryListSetting.Builder<MobEffect> statusEffects(String name) {
		return bind(RegistryListSetting.statusEffects(name));
	}
}
