package dev.myriadclient.api.render;

import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.RegistryListSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.setting.Settings;
import dev.myriadclient.api.world.Entities;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The standard way to pick entities to show (players, friends, monsters, crystals, pearls, …), each group with an
 * on/off setting and a colour from the theme, plus a list of extra entity types for anything the groups don't cover.
 * ESP, tracers, chams and radars use it so they all offer the same choices.
 *
 * <pre>{@code
 * private final EntityGroups targets = new EntityGroups(settings, Set.of(Group.PLAYERS, Group.FRIENDS));
 * int color = targets.color(entity);   // 0 = don't draw it
 * }</pre>
 */
public final class EntityGroups {
	/** The groups, each with its setting name and default theme colour. */
	public enum Group {
		/** Other players who aren't friends (fake ones included). */
		PLAYERS("Players", SettingColor.Mode.ACCENT),
		/** Players on your friends list. */
		FRIENDS("Friends", SettingColor.Mode.CYAN),
		/** You, shown only in third person. */
		SELF("Self", SettingColor.Mode.TEXT),
		/** Hostile mobs ({@link Entities.Kind#HOSTILE}). */
		MONSTERS("Monsters", SettingColor.Mode.RED),
		/** Passive mobs ({@link Entities.Kind#PASSIVE}). */
		ANIMALS("Animals", SettingColor.Mode.GREEN),
		/** End crystals. */
		CRYSTALS("Crystals", SettingColor.Mode.MAGENTA),
		/** Dropped items. */
		ITEMS("Items", SettingColor.Mode.YELLOW),
		/** Thrown ender pearls. */
		PEARLS("Pearls", SettingColor.Mode.SECONDARY),
		/** Boats and minecarts. */
		VEHICLES("Vehicles", SettingColor.Mode.BLUE),
		/** Armour stands. */
		ARMOR_STANDS("Armor Stands", SettingColor.Mode.TEXT),
		/** Item frames, glowing ones included. */
		ITEM_FRAMES("Item Frames", SettingColor.Mode.TEXT);

		/** The name of its toggle and colour setting ("Players", "Armor Stands"). */
		public final String title;
		/** The theme colour the group defaults to. */
		public final SettingColor.Mode color;

		Group(String title, SettingColor.Mode color) {
			this.title = title;
			this.color = color;
		}
	}

	private final Map<Group, BoolSetting> enabled = new EnumMap<>(Group.class);
	private final Map<Group, ColorSetting> colors = new EnumMap<>(Group.class);
	private final BoolSetting invisibles;
	private final RegistryListSetting<EntityType<?>> others;
	private final ColorSetting otherColor;

	/**
	 * Adds an "Entities" group of toggles (one per {@link Group}, then Invisibles and an Others list) and a "Colors"
	 * group to {@code settings}, with the groups in {@code onByDefault} switched on.
	 */
	public EntityGroups(Settings settings, Set<Group> onByDefault) {
		SettingGroup sgTargets = settings.group("Entities");
		SettingGroup sgColors = settings.group("Colors");
		for (Group g : Group.values()) {
			BoolSetting on = sgTargets.bool(g.title).defaultValue(onByDefault.contains(g)).build();
			enabled.put(g, on);
			colors.put(g, sgColors.color(g.title).defaultValue(SettingColor.role(g.color)).visible(on::get).build());
		}
		invisibles = sgTargets.bool("Invisibles").description("Include invisible entities.").defaultValue(true).build();
		others = sgTargets.entityTypes("Others").description("Any other entity types to include.").build();
		otherColor = sgColors.color("Others").defaultValue(SettingColor.role(SettingColor.Mode.MAGENTA)).visible(() -> !others.get().isEmpty()).build();
	}

	/**
	 * Which group {@code e} belongs to, or null for none of them. The local player is {@link Group#SELF}; friends are
	 * {@link Group#FRIENDS}, not players.
	 */
	public static @Nullable Group of(Entity e) {
		if (e == Minecraft.getInstance().player) return Group.SELF;
		if (e instanceof EndCrystal) return Group.CRYSTALS;
		if (e instanceof ItemEntity) return Group.ITEMS;
		if (e instanceof ThrownEnderpearl) return Group.PEARLS;
		if (e instanceof ArmorStand) return Group.ARMOR_STANDS;
		if (e instanceof ItemFrame) return Group.ITEM_FRAMES;
		if (e instanceof VehicleEntity) return Group.VEHICLES;
		return switch (Entities.kind(e)) {
			case PLAYER -> Entities.isFriend(e) ? Group.FRIENDS : Group.PLAYERS;
			case HOSTILE -> Group.MONSTERS;
			case PASSIVE -> Group.ANIMALS;
			case OTHER -> null;
		};
	}

	/**
	 * The ARGB colour to draw {@code e} in, or 0 when it isn't selected (dead, invisible with Invisibles off, or in no
	 * group that's on nor the Others list). Yourself only counts in third person.
	 */
	public int color(Entity e) {
		if (!e.isAlive()) return 0;
		if (e.isInvisible() && !invisibles.get()) return 0;
		Group g = of(e);
		if (g == Group.SELF && Minecraft.getInstance().options.getCameraType().isFirstPerson()) return 0;
		if (g != null && enabled.get(g).get()) return colors.get(g).argb();
		return others.contains(e.getType()) ? otherColor.argb() : 0;
	}

	/** Whether group {@code g}'s toggle is on. */
	public boolean isOn(Group g) {
		return enabled.get(g).get();
	}
}
