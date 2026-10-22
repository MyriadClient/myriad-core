package dev.myriadclient.api.service;

import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.util.Reach;

/**
 * The standard placement settings, Range and Air Place, so every module that places blocks offers the same ones. The
 * rest of how a block is placed follows the server ({@link Placement.Options#forServer()}).
 *
 * <pre>{@code
 * private final PlacementSettings placing = new PlacementSettings(sgGeneral);
 *
 * Myriad.placement().place(this, pos, slot, placing.options());
 * }</pre>
 */
public final class PlacementSettings {
	public final DoubleSetting range;
	public final BoolSetting airPlace;

	/** Adds Range and Air Place to {@code group}. */
	public PlacementSettings(SettingGroup group) {
		range = group.decimal("Range").description("How far away blocks are placed, at most what the server allows.")
			.defaultValue(4.5).range(1, 6).decimals(1).build();
		// Shown everywhere: 2b2t takes it and current Grim doesn't, and the anti-cheat profile can't tell the two apart.
		airPlace = group.bool("Air Place").description("Place blocks with nothing next to them, through the off hand. "
			+ "2b2t takes it; current Grim refuses it.").build();
	}

	/** Range, capped at what the server allows ({@link Reach#blockRange()}). */
	public double reach() {
		return Math.min(range.get(), Reach.blockRange());
	}

	/** {@link Placement.Options#forServer()} with this range and air placement. */
	public Placement.Options options() {
		return options(AntiCheat.inEffect(), reach());
	}

	Placement.Options options(AntiCheat.Profile p, double reach) {
		return Placement.Options.forProfile(p).withRange(reach).withAirPlace(airPlace.get());
	}
}
