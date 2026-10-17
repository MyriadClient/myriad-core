package dev.myriadclient.api.service;

import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.hook.Replacement;
import dev.myriadclient.api.hook.Service;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * Server-side rotation arbitration. Modules {@link #request} a rotation every tick they need one; each tick the
 * winning request is sent in the movement packet and all requests are cleared. The camera is not moved.
 *
 * <p>Yaw and pitch are arbitrated separately: pass {@link Float#NaN} for an axis you don't care about and another
 * module may take it (Elytra Fly steering pitch while an aura aims yaw). For each axis the highest priority request
 * wins; ties go to the first request.
 *
 * <p>With {@link Options}, a request can turn at a limited speed instead of snapping, and can correct your movement
 * for the rotation (see {@link Options#moveFix}). When requests stop, a rotation that was turning eases back to where
 * you look at the same speed instead of snapping back.
 *
 * <pre>{@code
 * // Request in TickEvent.Pre so move fix applies to this tick's movement.
 * Myriad.rotations().lookAt(this, target.getEyePosition(), Rotations.PRIORITY_HIGH,
 *     Rotations.Options.forServer().withTurnSpeed(turnSpeed.get()));
 * }</pre>
 */
@ApiStatus.NonExtendable
public interface Rotations {
	int PRIORITY_LOW = 0;
	int PRIORITY_NORMAL = 50;
	int PRIORITY_HIGH = 100;

	/**
	 * How a rotation is applied. Start from a preset and change what you need, so new options added in later versions
	 * keep their defaults in your code:
	 *
	 * <pre>{@code
	 * Rotations.Options o = Rotations.Options.forServer().withTurnSpeed(turnSpeed.get());
	 * }</pre>
	 */
	final class Options {
		/** Snaps to the rotation at once, movement unchanged. */
		public static final Options INSTANT = new Options(0, false);
		/** Snaps to the rotation at once, with {@link #moveFix()}: what Grim expects. */
		public static final Options MOVE_FIX = new Options(0, true);

		private final float turnSpeed;
		private final boolean moveFix;

		private Options(float turnSpeed, boolean moveFix) {
			this.turnSpeed = Math.max(0, turnSpeed);
			this.moveFix = moveFix;
		}

		/** {@link #MOVE_FIX} where the server checks movement against rotations ({@link AntiCheat#isStrict()}), else {@link #INSTANT}. */
		public static Options forServer() {
			return AntiCheat.strict() ? MOVE_FIX : INSTANT;
		}

		/**
		 * The most each axis turns per tick, in degrees, from the rotation the server last saw towards the target; 0
		 * snaps there at once. Strict anti-cheats flag big instant turns, so modules that aim at moving targets usually
		 * let the user pick a speed.
		 */
		public float turnSpeed() {
			return turnSpeed;
		}

		/**
		 * Makes your movement match the sent yaw, as anti-cheats that simulate movement (Grim) expect: the movement keys
		 * are re-pressed relative to the sent yaw to go the way you're steering as closely as 8 directions allow, and
		 * walking, jumping and sprinting use the sent yaw. Needs the request made before the player moves, e.g. in
		 * {@code TickEvent.Pre}.
		 */
		public boolean moveFix() {
			return moveFix;
		}

		public boolean isInstant() {
			return turnSpeed <= 0;
		}

		public Options withTurnSpeed(float turnSpeed) {
			return new Options(turnSpeed, moveFix);
		}

		public Options withMoveFix(boolean moveFix) {
			return new Options(turnSpeed, moveFix);
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Options other && other.turnSpeed == turnSpeed && other.moveFix == moveFix;
		}

		@Override
		public int hashCode() {
			return Float.hashCode(turnSpeed) * 31 + Boolean.hashCode(moveFix);
		}

		@Override
		public String toString() {
			return "Rotations.Options[turnSpeed=" + turnSpeed + ", moveFix=" + moveFix + "]";
		}
	}

	/** Requests a rotation for the next movement packet, applied instantly. */
	default void request(Object owner, float yaw, float pitch, int priority) {
		request(owner, yaw, pitch, priority, Options.INSTANT, null);
	}

	/**
	 * Like {@link #request(Object, float, float, int)}, then runs {@code afterSent} once the movement packet carrying
	 * this rotation has gone out, so the server already faces where you want when the action arrives (strict servers
	 * check the rotation of placements and hits). Doesn't run if a higher-priority request wins an axis you asked for.
	 */
	default void request(Object owner, float yaw, float pitch, int priority, Runnable afterSent) {
		request(owner, yaw, pitch, priority, Options.INSTANT, afterSent);
	}

	/**
	 * The full form. {@code afterSent} (may be null) runs only once the sent rotation has reached the target, which
	 * with a turn speed can take a few ticks of requesting.
	 */
	void request(Object owner, float yaw, float pitch, int priority, Options options, Runnable afterSent);

	default void lookAt(Object owner, Vec3 target, int priority) {
		lookAt(owner, target, priority, Options.INSTANT);
	}

	default void lookAt(Object owner, Vec3 target, int priority, Options options) {
		float[] r = anglesTo(target);
		request(owner, r[0], r[1], priority, options, null);
	}

	/**
	 * Makes this tick's movement packet carry a rotation even if you haven't turned, by nudging the sent pitch 0.01°.
	 * Vanilla only sends a rotation when you turn, and Grim only updates the look it remembers ("last look") from
	 * packets that carry one; asked for every tick, its last look is always exactly the rotation sent the tick before
	 * (what {@link #serverYaw()} and {@link #serverPitch()} say until this tick's packet goes out). Call it each tick
	 * it's needed, before the movement packet.
	 */
	void sendRotationThisTick();

	/**
	 * The rotation this tick's movement packet will carry, {yaw, pitch}, fixed from now on: requests made later this
	 * tick no longer change it. For packets that carry a rotation of their own (using an item), which Grim
	 * (BadPacketsJ) wants equal to the tick's. Item-use packets are given it automatically, except thrown items (a
	 * pearl): those keep their own aim, and fix the tick's rotation to it.
	 */
	float[] rotationForAction();

	/** Whether a rotation other than your real one was sent last tick (including while easing back). */
	boolean isRotating();

	/** Yaw/pitch the server currently believes the player has. */
	float serverYaw();

	float serverPitch();

	/** Whether the server already believes you face {@code yaw}/{@code pitch}, within {@code tolerance} degrees per axis. */
	default boolean isFacing(float yaw, float pitch, float tolerance) {
		float dy = Math.abs(Mth.wrapDegrees(yaw - serverYaw()));
		return dy <= tolerance && Math.abs(pitch - serverPitch()) <= tolerance;
	}

	/** Yaw and pitch from the player's eyes to {@code target}. */
	float[] anglesTo(Vec3 target);

	/** One step of a rotation turning towards its target, for {@link #TURN}: angles in degrees, speed 0 = snap. */
	record Turn(float from, float to, float speed, boolean yaw) {
	}

	/**
	 * Where a rotation is this tick, turning from the last one sent towards its target (core: at most the request's
	 * turn speed, the short way round for yaw). Called for the request winning each axis, with its owner as caller.
	 */
	Hook<Turn, Float> TURN = Hook.of("myriad:rotation_turn", "Rotation turn",
		"How a rotation turns towards its target each tick.");

	/**
	 * The base for a replacement of this service ({@link Service#ROTATIONS}): hands every call
	 * to the service it wraps (core's), so override only what you do differently. While it's in use it gets Myriad's
	 * events and {@link #activated()}/{@link #deactivated()}.
	 */
	abstract class Forwarding implements Rotations, Replacement {
		/** The service this one wraps: core's own. */
		protected final Rotations core;

		protected Forwarding(Rotations core) {
			this.core = Objects.requireNonNull(core);
		}

		@Override
		public void request(Object owner, float yaw, float pitch, int priority, Options options, Runnable afterSent) {
			core.request(owner, yaw, pitch, priority, options, afterSent);
		}

		@Override
		public void sendRotationThisTick() {
			core.sendRotationThisTick();
		}

		@Override
		public float[] rotationForAction() {
			return core.rotationForAction();
		}

		@Override
		public boolean isRotating() {
			return core.isRotating();
		}

		@Override
		public float serverYaw() {
			return core.serverYaw();
		}

		@Override
		public float serverPitch() {
			return core.serverPitch();
		}

		@Override
		public float[] anglesTo(Vec3 target) {
			return core.anglesTo(target);
		}
	}
}
