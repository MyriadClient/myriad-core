package dev.myriadclient.impl.elytra;

import dev.myriadclient.api.elytra.NoDurability;
import dev.myriadclient.api.job.Why;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * {@link dev.myriadclient.api.elytra.Elytra#altitude}: crosses open country without fireworks, "pitch 40" style. It
 * dives until fast, pulls up hard, then eases back to level: pulling up gives back more height than the speed it costs,
 * so the cycle holds the cruise height (diving harder above it and climbing more below). The first dive from a slow
 * glide drops 50 or so blocks. With No Durability where the glide is simulated, a slower cycle, so chunks sent just in
 * time (2b2t's, in new terrain) are in before the swaps reach them.
 */
final class AltitudeFlight extends FlightEngine {
	private enum Phase {
		DIVE, PULL_UP, CLIMB
	}

	/**
	 * Worked out on vanilla's glide physics: dive at {@code DIVE_PITCH} until faster than the threshold (blocks a
	 * second), snap up to {@code CLIMB_PITCH} at {@code PULL_RATE} a tick, then ease back to level at {@code EASE_RATE}.
	 * The threshold rises by {@code HOLD_GAIN} for every block above the cruise height (more speed, less climb) and falls
	 * below it, which keeps the cycle at that height: about 28 blocks a second.
	 */
	private static final float DIVE_PITCH = 36, CLIMB_PITCH = -49, PULL_RATE = 5, EASE_RATE = 0.8f;
	private static final double DIVE_SPEED = 52, HOLD_GAIN = 0.2, MIN_DIVE_SPEED = 36, MAX_DIVE_SPEED = 58;
	/**
	 * With No Durability's chest swaps: about 18 blocks a second. Down there the climb gains most from about 40 and
	 * nothing from 33, so the threshold works the other way round: it falls by {@code SLOW_HOLD_GAIN} for every block
	 * above the cruise height (less climb) and rises below it. {@code SLOW_LATE_SPEED} while chunks still come in late.
	 */
	private static final double SLOW_DIVE_SPEED = 34.5, SLOW_HOLD_GAIN = 0.1, SLOW_MIN_DIVE_SPEED = 33, SLOW_MAX_DIVE_SPEED = 40, SLOW_LATE_SPEED = 36;
	/** After a run of setbacks: a gentle descent, no diving or climbing, until the server agrees again. */
	private static final float STEADY_PITCH = 8;
	/** Chunks this many seconds of flight ahead that haven't loaded: climb at {@code SLOW_PITCH} (speed for height) down to {@code SLOW_SPEED}. */
	private static final double LOAD_AHEAD_SECONDS = 4, SLOW_SPEED = 15;
	private static final float SLOW_PITCH = -20;
	/** Ground this far ahead raises the cruise height to this much above it. */
	private static final int TERRAIN_AHEAD = 160;
	private static final double TERRAIN_CLEARANCE = 24;
	/** Closer than this many ticks of flight, ground up to this far below you means pull up, whatever the cycle. */
	private static final int PULL_UP_TICKS = 60;
	private static final double PULL_UP_MARGIN = 8;
	/** Landing: the pitch it comes down at, levelling out this close above the ground so the touchdown is gentle. */
	private static final float LAND_PITCH = 20;
	private static final double FLARE_HEIGHT = 16;

	private Phase phase = Phase.DIVE;
	private float climbPitch;
	private double cruiseY = Double.NaN;

	AltitudeFlight(NoDurability.Owner owner) {
		super(owner);
	}

	@Override
	protected void resetFlight() {
		phase = Phase.DIVE;
		cruiseY = Double.NaN;
	}

	@Override
	public String status() {
		return landing ? "Landing" : steadyTicks > 0 ? "Steadying" : Double.isNaN(cruiseY) ? null : String.format("Y%.0f", cruiseY);
	}

	@Override
	protected State notGliding() {
		var p = mc.player;
		phase = Phase.DIVE;
		cruiseY = Double.NaN;
		if (p.onGround()) {
			stuck(Why.of(Why.BLOCKED, "on the ground: Altitude takes off only from the air"));
			return State.STUCK;
		}
		// Open the elytra once you're falling: off a ledge, or on the way down from a jump.
		if (p.getDeltaMovement().y < -0.3) wantOpen = true;
		return State.CLIMBING;
	}

	@Override
	protected State glide() {
		var p = mc.player;
		if (Double.isNaN(cruiseY)) cruiseY = Double.isNaN(height) ? p.getY() : height;
		cruiseY = Math.max(cruiseY, Double.isNaN(height) ? cruiseY : height);
		// Higher ground ahead raises the cruise height in time to clear it (it's never lowered again by itself).
		Vec3 dir = Vec3.directionFromRotation(0, heading);
		cruiseY = Math.max(cruiseY, groundAhead(dir, TERRAIN_AHEAD) + TERRAIN_CLEARANCE);

		double speed = p.getDeltaMovement().length() * 20;
		// Too close to clear by cruising: pull up and keep climbing (slowing down if it must: settling onto a slope
		// slowly beats hitting it at full speed).
		double near = Math.max(16, Math.sqrt(p.getDeltaMovement().horizontalDistanceSqr()) * PULL_UP_TICKS);
		if (groundAhead(dir, near) > p.getY() - PULL_UP_MARGIN) {
			climbPitch = CLIMB_PITCH;
			phase = Phase.CLIMB;
			spoof(heading, CLIMB_PITCH);
			return State.CLIMBING;
		}
		if (landing) {
			spoof(heading, aboveGround() > FLARE_HEIGHT ? LAND_PITCH : 0);
			return State.LANDING;
		}
		if (steadyTicks > 0) {
			steadyTicks--;
			phase = Phase.DIVE;
			spoof(heading, STEADY_PITCH);
			return State.CRUISING;
		}
		// Chunks ahead not in yet: slow down (climbing) until they are, rather than fly into the edge of the world.
		if (speed > SLOW_SPEED && !loadedAhead(dir, speed * LOAD_AHEAD_SECONDS)) {
			phase = Phase.DIVE;
			spoof(heading, SLOW_PITCH);
			return State.CRUISING;
		}
		double diveUntil = Mth.clamp(DIVE_SPEED + HOLD_GAIN * (p.getY() - cruiseY), MIN_DIVE_SPEED, MAX_DIVE_SPEED);
		if (chestSwaps()) {
			diveUntil = Mth.clamp(SLOW_DIVE_SPEED - SLOW_HOLD_GAIN * (p.getY() - cruiseY), SLOW_MIN_DIVE_SPEED, SLOW_MAX_DIVE_SPEED);
			// While No Durability's swaps wait for chunks, the slowest that still climbs.
			if (noDur.chunksLate()) diveUntil = Math.min(diveUntil, SLOW_LATE_SPEED);
		}
		float pitch = switch (phase) {
			case DIVE -> {
				if (speed > diveUntil) {
					phase = Phase.PULL_UP;
					climbPitch = 0;
				}
				yield DIVE_PITCH;
			}
			case PULL_UP -> {
				climbPitch = Math.max(CLIMB_PITCH, climbPitch - PULL_RATE);
				if (climbPitch <= CLIMB_PITCH) phase = Phase.CLIMB;
				yield climbPitch;
			}
			case CLIMB -> {
				climbPitch = Math.min(0, climbPitch + EASE_RATE);
				if (climbPitch >= 0) phase = Phase.DIVE;
				yield climbPitch;
			}
		};
		spoof(heading, pitch);
		return State.CRUISING;
	}
}
