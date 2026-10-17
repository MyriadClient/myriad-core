package dev.myriadclient.api.combat;

import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.setting.Settings;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.entity.Entity;

/**
 * The standard "Targets" settings group, so every combat module offers players the same choices with the same names.
 * Create it as a field, then ask it for targets:
 *
 * <pre>{@code
 * private final TargetSettings targets = new TargetSettings(settings, Targets.Type.PLAYERS);
 *
 * Entity target = targets.query().range(range.get()).best();
 * }</pre>
 */
public final class TargetSettings {
	public final BoolSetting players, hostiles, animals, villagers, bosses, projectiles;
	public final BoolSetting onlyAngry, ignoreInvisibles, ignoreNamed, ignoreCreative, friends, throughWalls;
	public final EnumSetting<Targets.Sort> sort;

	/** With players on by default. */
	public TargetSettings(Settings settings) {
		this(settings, Targets.Type.PLAYERS);
	}

	/** @param onByDefault the target types switched on until the player changes them */
	public TargetSettings(Settings settings, Targets.Type... onByDefault) {
		Set<Targets.Type> on = onByDefault.length == 0 ? EnumSet.noneOf(Targets.Type.class) : EnumSet.copyOf(List.of(onByDefault));
		SettingGroup sg = settings.group("Targets");
		players = sg.bool("Players").defaultValue(on.contains(Targets.Type.PLAYERS)).build();
		bosses = sg.bool("Bosses").description("Withers, wardens, the ender dragon.").defaultValue(on.contains(Targets.Type.BOSSES)).build();
		animals = sg.bool("Animals").defaultValue(on.contains(Targets.Type.ANIMALS)).build();
		villagers = sg.bool("Villagers").defaultValue(on.contains(Targets.Type.VILLAGERS)).build();
		hostiles = sg.bool("Hostiles").defaultValue(on.contains(Targets.Type.HOSTILES)).build();
		// On by default: hitting a calm zombified piglin or enderman starts a fight you didn't ask for.
		onlyAngry = sg.bool("Only Angry").description("Neutral mobs (endermen, zombified piglins, wolves, bees) only once they're angry.")
			.defaultValue(true).visible(hostiles::get).build();
		projectiles = sg.bool("Projectiles").description("Fireballs and shulker bullets.").defaultValue(on.contains(Targets.Type.PROJECTILES)).build();
		ignoreInvisibles = sg.bool("Ignore Invisibles").build();
		ignoreNamed = sg.bool("Ignore Named").description("Skip mobs with a name tag.").build();
		ignoreCreative = sg.bool("Ignore Creative").build();
		friends = sg.bool("Friends").description("Also target friends.").build();
		throughWalls = sg.bool("Through Walls").defaultValue(true).build();
		sort = sg.enumSetting("Sort", Targets.Sort.DISTANCE).description("Which target to prefer.").build();
	}

	public Set<Targets.Type> types() {
		Set<Targets.Type> t = EnumSet.noneOf(Targets.Type.class);
		if (players.get()) t.add(Targets.Type.PLAYERS);
		if (hostiles.get()) t.add(Targets.Type.HOSTILES);
		if (animals.get()) t.add(Targets.Type.ANIMALS);
		if (villagers.get()) t.add(Targets.Type.VILLAGERS);
		if (bosses.get()) t.add(Targets.Type.BOSSES);
		if (projectiles.get()) t.add(Targets.Type.PROJECTILES);
		return t;
	}

	/** A query set up from these settings; add a range (and anything else) and call {@code best()} or {@code list()}. */
	public Targets.Query query() {
		return Targets.query().types(types()).onlyAngry(onlyAngry.get()).invisibles(!ignoreInvisibles.get()).named(!ignoreNamed.get())
			.creative(!ignoreCreative.get()).friends(friends.get()).throughWalls(throughWalls.get()).sort(sort.get());
	}

	/** Whether {@code entity} would be a target within {@code range}. */
	public boolean test(Entity entity, double range) {
		return query().range(range).test(entity);
	}

	public @Nullable Entity best(double range) {
		return query().range(range).best();
	}
}
