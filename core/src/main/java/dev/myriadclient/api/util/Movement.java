package dev.myriadclient.api.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/** The local player's movement: which way the keys point, and setting speed along it. Speed/flight features use these. */
public final class Movement {
	/** Sprinting speed on the ground with no effects, in blocks per tick. */
	private static final double SPRINT_SPEED = 0.2873;
	/** The movement speed attribute with no effects. */
	private static final double WALK_ATTRIBUTE = 0.1;

	private Movement() {
	}

	private static LocalPlayer player() {
		return Minecraft.getInstance().player;
	}

	/** True while any movement key is held. */
	public static boolean hasInput() {
		var p = player();
		if (p == null) return false;
		var in = p.input.keyPresses;
		return in.forward() != in.backward() || in.left() != in.right();
	}

	/**
	 * The yaw the movement keys point in (the camera's yaw + 90 while holding only D, for example), or {@code NaN}
	 * with no input. Combine with {@link MathUtil#horizontalDirection(float)} for a direction vector.
	 */
	public static float inputYaw() {
		return inputYaw(player() == null ? 0 : player().getYRot());
	}

	/** Like {@link #inputYaw()} relative to {@code yaw} instead of where the camera faces (e.g. a locked flight yaw). */
	public static float inputYaw(float yaw) {
		var p = player();
		if (p == null) return Float.NaN;
		var in = p.input.keyPresses;
		int forward = (in.forward() ? 1 : 0) - (in.backward() ? 1 : 0);
		int strafe = (in.left() ? 1 : 0) - (in.right() ? 1 : 0);
		if (forward == 0 && strafe == 0) return Float.NaN;
		// atan2 of the input in the player's frame; strafing left turns the heading anticlockwise (negative yaw).
		float offset = (float) Math.toDegrees(Math.atan2(-strafe, forward));
		return yaw + offset;
	}

	/** Current speed along the ground, in blocks per tick. */
	public static double horizontalSpeed() {
		var p = player();
		if (p == null) return 0;
		Vec3 v = p.getDeltaMovement();
		return Math.sqrt(v.x * v.x + v.z * v.z);
	}

	/**
	 * How fast sprinting on the ground carries you, in blocks per tick: about 0.2873 normally, more with Speed or Soul
	 * Speed, less with Slowness. Speed features build on it ({@code setHorizontalSpeed(baseSpeed() * 1.2)}) so they scale
	 * with your effects instead of fighting them.
	 */
	public static double baseSpeed() {
		var p = player();
		if (p == null) return SPRINT_SPEED;
		// The movement speed attribute holds every effect; take sprinting's own boost out and put the standard one back.
		double attribute = p.getAttributeValue(Attributes.MOVEMENT_SPEED) / (p.isSprinting() ? 1.3 : 1);
		return SPRINT_SPEED * attribute / WALK_ATTRIBUTE;
	}

	/** Stops horizontal movement, keeping vertical (falling, jumping). */
	public static void stop() {
		var p = player();
		if (p != null) p.setDeltaMovement(0, p.getDeltaMovement().y, 0);
	}

	/** Sets horizontal velocity to {@code speed} blocks per tick in the input direction (or stops with no input). */
	public static void setHorizontalSpeed(double speed) {
		var p = player();
		if (p == null) return;
		float yaw = inputYaw();
		Vec3 v = p.getDeltaMovement();
		if (Float.isNaN(yaw)) {
			p.setDeltaMovement(0, v.y, 0);
			return;
		}
		Vec3 dir = MathUtil.horizontalDirection(yaw).scale(speed);
		p.setDeltaMovement(dir.x, v.y, dir.z);
	}
}
