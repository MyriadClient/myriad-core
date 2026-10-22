package dev.myriadclient.api.service;

import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.SettingGroup;

/**
 * The standard "Turn Speed" setting, so every module that turns you means the same by it. Add it to a group, then ask
 * it for options:
 *
 * <pre>{@code
 * private final RotationSettings rotation = new RotationSettings(sgGeneral);
 *
 * Myriad.rotations().lookAt(this, target.getEyePosition(), Rotations.PRIORITY_NORMAL, rotation.options());
 * }</pre>
 */
public final class RotationSettings {
	public final IntSetting turnSpeed;

	/** Adds Turn Speed to {@code group}. */
	public RotationSettings(SettingGroup group) {
		turnSpeed = group.integer("Turn Speed")
			.description("Most degrees to turn per tick; 0 turns at once (fine on Grim, some anti-cheats want it limited).")
			.defaultValue(0).range(0, 180).build();
	}

	/** {@link Rotations.Options#forServer()} at this turn speed. */
	public Rotations.Options options() {
		return Rotations.Options.forServer().withTurnSpeed(turnSpeed.get());
	}
}
