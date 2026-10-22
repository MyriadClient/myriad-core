package dev.myriadclient.api.service;

import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.SettingGroup;

/**
 * The standard building settings, so every module that builds offers the same ones: {@link PlacementSettings}'s
 * Range and Air Place, Actions Per Tick, Break Wrong Blocks, and {@link BreakingSettings}' Mode and Double Break for
 * those breaks. The rest follows the server ({@link Building.Options#forServer()}).
 *
 * <pre>{@code
 * private final BuildingSettings building = new BuildingSettings(sgGeneral);
 *
 * Myriad.building().start(this, blueprint, building.options());
 * }</pre>
 */
public final class BuildingSettings {
	public final PlacementSettings placing;
	public final IntSetting actionsPerTick;
	public final BoolSetting breakWrong;
	public final BreakingSettings breaking;

	/** Adds the settings to {@code group}, with Break Wrong Blocks off by default. */
	public BuildingSettings(SettingGroup group) {
		this(group, false);
	}

	/** As {@link #BuildingSettings(SettingGroup)}, with Break Wrong Blocks on or off by default. */
	public BuildingSettings(SettingGroup group, boolean breakWrongByDefault) {
		placing = new PlacementSettings(group);
		actionsPerTick = group.integer("Actions Per Tick").description("Most blocks placed or clicked each tick. Servers drop placements past 9 "
			+ "in a third of a second (about 27 a second), so more than 2 only helps in bursts.").defaultValue(2).range(1, 6).build();
		breakWrong = group.bool("Break Wrong Blocks").description("Break blocks in the way of what's to be built. Off, they're left and shown "
			+ "as blocked.").defaultValue(breakWrongByDefault).build();
		breaking = new BreakingSettings(group, breakWrong::get);
	}

	/** {@link Building.Options#forServer()} with these settings; blocks are broken as far away as they're placed. */
	public Building.Options options() {
		return options(AntiCheat.inEffect(), placing.reach());
	}

	Building.Options options(AntiCheat.Profile p, double reach) {
		return Building.Options.forProfile(p).withPlacing(placing.options(p, reach)).withBreaking(breaking.options(p).withRange(reach))
			.withBreakWrong(breakWrong.get()).withPlacesPerTick(actionsPerTick.get());
	}
}
