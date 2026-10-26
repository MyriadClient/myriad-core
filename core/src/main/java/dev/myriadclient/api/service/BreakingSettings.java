package dev.myriadclient.api.service;

import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.SettingGroup;

import java.util.function.Supplier;

/**
 * The standard breaking settings, Mode and Double Break, so every module that mines offers the same choice. The rest of
 * how a block is broken follows the server ({@link Breaking.Options#forServer()}).
 *
 * <pre>{@code
 * private final BreakingSettings breaking = new BreakingSettings(sgGeneral);
 *
 * Myriad.breaking().breakBlock(this, pos, 0, breaking.options());
 * }</pre>
 */
public final class BreakingSettings {
	public enum Mode {
		/** As fast as the server takes without refusing a finish: {@link Breaking.Options#forServer()}. */
		PACKET,
		/**
		 * Every block at 70% of its time. Where an anti-cheat times breaks that's {@link Breaking.Mode#FAST_GRIM}'s
		 * decoys (2b2t; current Grim flags each one), elsewhere {@link Breaking.Mode#FAST}.
		 */
		FAST
	}

	public final EnumSetting<Mode> mode;
	public final BoolSetting doubleBreak;

	/** Adds Mode and Double Break to {@code group}. */
	public BreakingSettings(SettingGroup group) {
		this(group, () -> true);
	}

	/** Adds Mode and Double Break to {@code group}, shown only while {@code visible} says so. */
	public BreakingSettings(SettingGroup group, Supplier<Boolean> visible) {
		mode = group.oneOf("Mode", Mode.PACKET).description("Packet: as fast as the server takes, with your hand free (on 2b2t, whose Grim a "
			+ "decoy start fools, as Fast). Fast: every block at 70% of its time; past Grim a decoy start above the build limit hides the speed "
			+ "(needs ViaVersion, as 2b2t has), and current Grim builds flag each decoy as AirLiquidBreak.").visible(visible).build();
		doubleBreak = group.bool("Double Break").description("Mine a second block while the server finishes the first, where blocks finish early.")
			.defaultValue(true).visible(() -> visible.get() && (mode.get() == Mode.FAST || AntiCheat.inEffect().breakTiming() != AntiCheat.BreakTiming.CHECKED)).build();
	}

	/** {@link Breaking.Options#forServer()} in this mode. */
	public Breaking.Options options() {
		return options(AntiCheat.inEffect());
	}

	Breaking.Options options(AntiCheat.Profile p) {
		Breaking.Options o = Breaking.Options.forProfile(p);
		if (mode.get() == Mode.FAST) o = o.withMode(p.breakTiming() == AntiCheat.BreakTiming.SERVER ? Breaking.Mode.FAST : Breaking.Mode.FAST_GRIM);
		boolean early = o.mode() == Breaking.Mode.FAST || o.mode() == Breaking.Mode.FAST_GRIM;
		return o.withDoubleBreak(early && doubleBreak.get());
	}
}
