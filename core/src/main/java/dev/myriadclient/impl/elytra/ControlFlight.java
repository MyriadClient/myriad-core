package dev.myriadclient.impl.elytra;

import dev.myriadclient.api.elytra.NoDurability;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.GlideEvent;
import dev.myriadclient.api.job.Why;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * {@link dev.myriadclient.api.elytra.Elytra#control}: flies the glide itself, straight along the heading at the speed
 * asked for, holding a height. Turning and slowing down are instant; speed builds as fast as NoCheatPlus's elytra check
 * allows (it predicts a vanilla glide from your last move and your look, and takes up to 0.1 blocks a tick more,
 * whatever the direction), about 1.8 blocks a second each tick. It sends the heading level as its look, so that
 * prediction is the glide flown. A setback starts the speed again from nothing. It doesn't fly into chunks that haven't
 * loaded: it waits at their edge.
 */
final class ControlFlight extends FlightEngine {
	/** Climb and descent while holding the height, blocks a tick (10 a second). */
	private static final double VERTICAL = 0.5;
	/** NoCheatPlus's slack over the vanilla glide it predicts, blocks a tick, less a margin; and the share of the speed vanilla steers towards the look each tick. */
	private static final double SPEED_SLACK = 0.09, STEER = 0.1;
	/** How far ahead (blocks) higher ground raises the height held, and the clearance over it. */
	private static final int TERRAIN_AHEAD = 96;
	private static final double CLEARANCE = 16;

	/** The horizontal movement flown last tick, blocks a tick: what this tick builds on. */
	private Vec3 flown = Vec3.ZERO;
	/** The height held this glide (where it started, unless one was given). */
	private double holdY = Double.NaN;
	/** Waiting at the edge of chunks that haven't loaded. */
	private boolean atEdge;

	ControlFlight(NoDurability.Owner owner) {
		super(owner);
	}

	@Override
	protected void resetFlight() {
		flown = Vec3.ZERO;
		holdY = Double.NaN;
		atEdge = false;
	}

	@Override
	protected void setBack() {
		flown = Vec3.ZERO;
	}

	@Override
	public String status() {
		if (landing) return "Landing";
		return flown.lengthSqr() > 1.0E-6 ? String.format("%.0f b/s", flown.horizontalDistance() * 20) : null;
	}

	@Override
	protected State notGliding() {
		var p = mc.player;
		flown = Vec3.ZERO;
		holdY = Double.NaN;
		if (speed <= 0 || landing) {
			stuck(Why.of(Why.BLOCKED, "nowhere to fly"));
			return State.STUCK;
		}
		// Takes off by itself: a jump, and the elytra open on the way down.
		if (p.onGround()) takeOffJump = true;
		else if (p.getDeltaMovement().y < 0) wantOpen = true;
		return State.CLIMBING;
	}

	@Override
	protected State glide() {
		var p = mc.player;
		if (Double.isNaN(holdY)) holdY = p.getY();
		spoof(heading, 0);
		if (atEdge && !landing) {
			stuck(Why.of(Why.UNLOADED, "waiting for chunks ahead to load"));
			return State.STUCK;
		}
		return Double.isNaN(climb) && p.getY() < target() - 2 ? State.CLIMBING : State.CRUISING;
	}

	/** The height to hold: the one given (or where the glide started), at least {@link #CLEARANCE} above ground ahead. */
	private double target() {
		double y = Double.isNaN(height) ? holdY : height;
		return Math.max(y, groundAhead(Vec3.directionFromRotation(0, heading), TERRAIN_AHEAD) + CLEARANCE);
	}

	/** Control flies the glide itself: its movement replaces vanilla's. */
	@Subscribe
	private void onGlide(GlideEvent e) {
		var p = mc.player;
		if (p == null || !spoofing) return;
		Vec3 v = movement(p);
		if (v != null) e.setVelocity(v);
	}

	/** This tick's glide: along the heading at the speed asked for, holding the height; null in water or lava. */
	private Vec3 movement(LocalPlayer p) {
		if (p.isInWater() || p.isInLava()) {
			flown = Vec3.ZERO;
			return null;
		}
		// The heading as it is now (it may have changed since the rotation went out): NoCheatPlus's slack is the same
		// whatever the direction.
		Vec3 look = Vec3.directionFromRotation(0, heading);
		Vec3 wanted = landing ? Vec3.ZERO : look.scale(speed / 20);
		// As fast as NoCheatPlus's elytra check allows: the vanilla glide from the last move steered to the look, plus its slack.
		double h = flown.horizontalDistance();
		Vec3 glide = flown.add(look.scale(h).subtract(flown).scale(STEER));
		double most = glide.horizontalDistance() + SPEED_SLACK;
		flown = wanted.length() > most ? wanted.normalize().scale(most) : wanted;
		// Not into chunks that haven't loaded: wait at their edge.
		Vec3 next = p.position().add(flown.scale(4));
		atEdge = !p.level().hasChunk(Mth.floor(next.x) >> 4, Mth.floor(next.z) >> 4);
		if (atEdge) flown = Vec3.ZERO;

		double y;
		if (landing) y = -VERTICAL;
		else if (!Double.isNaN(climb)) y = climb / 20;
		else y = Mth.clamp((target() - p.getY()) * 0.25, -VERTICAL, VERTICAL);
		return new Vec3(flown.x, y, flown.z);
	}
}
