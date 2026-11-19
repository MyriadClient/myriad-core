package dev.myriadclient.api.service;

import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.SettingGroup;

/**
 * The standard building settings, so every module that builds offers the same ones: {@link PlacementSettings}'s
 * Range and Air Place, Break Wrong Blocks, and {@link BreakingSettings}' Mode and Double Break for those breaks. Builds
 * place as fast as the server takes placements (core keeps within its limit, 9 in 300 ms on Paper). The rest follows the server ({@link Building.Options#forServer()}).
 *
 * <pre>{@code
 * private final BuildingSettings building = new BuildingSettings(sgGeneral);
 *
 * Myriad.building().start(this, blueprint, building.options());
 * }</pre>
 */
public final class BuildingSettings {
	/** Range and Air Place. */
	public final PlacementSettings placing;
	/**
	 * Hidden and no longer used: builds place as fast as the server takes placements. Kept so code that reads it still
	 * runs.
	 *
	 * @deprecated builds aren't capped per tick any more; there's nothing to set.
	 */
	@Deprecated
	public final IntSetting actionsPerTick;
	/** Break Wrong Blocks: break what's in the way of the build; off, it's left and shown as blocked. */
	public final BoolSetting breakWrong;
	/** Mode and Double Break for the build's breaks, shown only while Break Wrong Blocks is on. */
	public final BreakingSettings breaking;

	/** Adds the settings to {@code group}, with Break Wrong Blocks off by default. */
	public BuildingSettings(SettingGroup group) {
		this(group, false);
	}

	/** As {@link #BuildingSettings(SettingGroup)}, with Break Wrong Blocks on or off by default. */
	public BuildingSettings(SettingGroup group, boolean breakWrongByDefault) {
		placing = new PlacementSettings(group);
		actionsPerTick = group.integer("Actions Per Tick").description("No longer used: builds place as fast as the server takes placements.")
			.defaultValue(9).range(1, 9).visible(() -> false).build();
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
			.withBreakWrong(breakWrong.get()).withPlacesPerTick(Integer.MAX_VALUE);
	}
}
