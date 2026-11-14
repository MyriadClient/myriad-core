package dev.myriadclient.api.util;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Angles, directions and geometry. Angles are Minecraft's: yaw 0 faces south (+Z) and grows clockwise seen from
 * above (90 faces west), pitch is positive looking down.
 */
public final class MathUtil {
	private MathUtil() {
	}

	/**
	 * {@code [yaw, pitch]} in degrees to look from {@code from} at {@code to}: yaw wrapped to -180..180, pitch clamped to
	 * -90..90 (positive down). Pass the eye position as {@code from} for where the player would look.
	 */
	public static float[] anglesTo(Vec3 from, Vec3 to) {
		double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
		return new float[]{Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90f, 90f)};
	}

	/** The yaw in degrees (-180..180) from {@code from} towards {@code to}, ignoring height. */
	public static float yawTo(Vec3 from, Vec3 to) {
		return anglesTo(from, to)[0];
	}

	/**
	 * Signed shortest turn in degrees from angle {@code a} to angle {@code b}, in -180..180: positive turns clockwise
	 * (to the right) for yaws. Inputs needn't be wrapped.
	 */
	public static float angleDifference(float a, float b) {
		return Mth.wrapDegrees(b - a);
	}

	/**
	 * The angle in degrees (0..180) between where {@code yaw}/{@code pitch} looks from {@code from} and the direction to
	 * {@code to}: 0 is dead ahead. {@code to} equal to {@code from} gives NaN.
	 */
	public static float angleTo(Vec3 from, float yaw, float pitch, Vec3 to) {
		Vec3 look = direction(yaw, pitch), dir = to.subtract(from).normalize();
		return (float) Math.toDegrees(Math.acos(Mth.clamp(look.dot(dir), -1, 1)));
	}

	/** Unit vector for a look direction given as yaw and pitch in degrees (yaw 0 = +Z, pitch 90 = straight down). */
	public static Vec3 direction(float yaw, float pitch) {
		return Vec3.directionFromRotation(pitch, yaw);
	}

	/** Unit vector along the ground (y = 0) for {@code yaw} in degrees: 0 gives +Z, 90 gives -X. */
	public static Vec3 horizontalDirection(float yaw) {
		double r = Math.toRadians(yaw);
		return new Vec3(-Math.sin(r), 0, Math.cos(r));
	}

	/** The point in {@code box} closest to {@code point} ({@code point} itself if it's inside). */
	public static Vec3 closestPoint(AABB box, Vec3 point) {
		return new Vec3(Mth.clamp(point.x, box.minX, box.maxX), Mth.clamp(point.y, box.minY, box.maxY),
			Mth.clamp(point.z, box.minZ, box.maxZ));
	}

	/** Distance in blocks from {@code point} to the nearest point of {@code box}; 0 if it's inside. */
	public static double distanceTo(AABB box, Vec3 point) {
		return closestPoint(box, point).distanceTo(point);
	}

	/** Distance in blocks between {@code a} and {@code b} on the ground plane, ignoring y. */
	public static double horizontalDistance(Vec3 a, Vec3 b) {
		double dx = a.x - b.x, dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** The point {@code t} of the way from {@code a} to {@code b} (0 = a, 1 = b), unclamped. */
	public static Vec3 lerp(double t, Vec3 a, Vec3 b) {
		return new Vec3(Mth.lerp(t, a.x, b.x), Mth.lerp(t, a.y, b.y), Mth.lerp(t, a.z, b.z));
	}

	/** Where {@code value} sits between {@code from} and {@code to}, as 0..1 (unclamped); 0 when the range is empty. */
	public static double inverseLerp(double value, double from, double to) {
		return from == to ? 0 : (value - from) / (to - from);
	}

	/** Maps {@code value} from one range to another (unclamped). */
	public static double map(double value, double fromMin, double fromMax, double toMin, double toMax) {
		return toMin + inverseLerp(value, fromMin, fromMax) * (toMax - toMin);
	}

	/** Rounds to the nearest multiple of {@code step}, e.g. a yaw snapped to 45°; {@code value} unchanged if step &lt;= 0. */
	public static double snap(double value, double step) {
		return step <= 0 ? value : Math.round(value / step) * step;
	}
}
