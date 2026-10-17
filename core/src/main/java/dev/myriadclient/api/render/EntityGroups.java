package dev.myriadclient.api.render;

import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.RegistryListSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.setting.Settings;
import dev.myriadclient.api.util.Entities;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.entity.vehicle.VehicleEntity;

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
	public enum Group {
		PLAYERS("Players", SettingColor.Mode.ACCENT),
		FRIENDS("Friends", SettingColor.Mode.CYAN),
		SELF("Self", SettingColor.Mode.TEXT),
		MONSTERS("Monsters", SettingColor.Mode.RED),
		ANIMALS("Animals", SettingColor.Mode.GREEN),
		CRYSTALS("Crystals", SettingColor.Mode.MAGENTA),
		ITEMS("Items", SettingColor.Mode.YELLOW),
		PEARLS("Pearls", SettingColor.Mode.SECONDARY),
		VEHICLES("Vehicles", SettingColor.Mode.BLUE),
		ARMOR_STANDS("Armor Stands", SettingColor.Mode.TEXT),
		ITEM_FRAMES("Item Frames", SettingColor.Mode.TEXT);

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

	/** Adds an "Entities" group of toggles and a "Colors" group to {@code settings}. */
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

	/** Which group {@code e} belongs to, or null for none of them. */
	public static Group of(Entity e) {
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

	/** The colour to draw {@code e} in, or 0 when it isn't selected. Yourself only counts in third person. */
	public int color(Entity e) {
		if (!e.isAlive()) return 0;
		if (e.isInvisible() && !invisibles.get()) return 0;
		Group g = of(e);
		if (g == Group.SELF && Minecraft.getInstance().options.getCameraType().isFirstPerson()) return 0;
		if (g != null && enabled.get(g).get()) return colors.get(g).argb();
		return others.contains(e.getType()) ? otherColor.argb() : 0;
	}

	public boolean isOn(Group g) {
		return enabled.get(g).get();
	}
}
