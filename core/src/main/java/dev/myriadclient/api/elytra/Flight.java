package dev.myriadclient.api.elytra;

import dev.myriadclient.api.job.Why;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * An elytra flight that flies itself along a heading: {@link Elytra#altitude} crosses open country holding its height
 * without fireworks, {@link Elytra#control} flies straight at a set speed where the server doesn't simulate the glide.
 * Tell it where to go, {@link #start()} it, and steer it as you go; the camera stays the player's throughout (the
 * flight's rotation only goes to the server and the glide physics).
 * <p>
 * Either way it keeps the elytra from wearing while its owner says so ({@link NoDurability}), catches a glide dropped
 * in mid-air, puts elytras worn down to {@link #preserve} aside for the freshest other one (in mid-air too), puts a
 * spare on when the worn one has fewer uses left than the way down takes, slows down for chunks that haven't loaded,
 * and rides out setbacks: a few in a row and it glides gently for a while. When it can't go on (the last elytra
 * nearly worn out) or is told to ({@link #land()}), it comes down, and {@link #onLanded} hears it once it's down.
 *
 * <pre>{@code
 * private final Flight flight = Myriad.elytra().altitude(this);   // this implements NoDurability.Owner
 *
 * protected void onEnable() {
 *     flight.preserve(5).onLanded(why -> disable()).start();
 * }
 *
 * @Subscribe(priority = Priority.BEFORE_ACTIONS + 20)   // before the flight's own tick
 * private void onTick(TickEvent.Pre e) {
 *     flight.heading(yawTowards(next));
 * }
 * }</pre>
 */
@ApiStatus.NonExtendable
public interface Flight {
	enum State {
		/** Taking off, opening the elytra, or climbing to a height it should be at (or over ground ahead). */
		CLIMBING,
		/** Flying along the heading. */
		CRUISING,
		/** Coming down to land: told to, or it can't go on ({@link #why()} says which). */
		LANDING,
		/** Down on the ground after landing, or not started. */
		DOWN,
		/** Wants to fly but can't for now; {@link #why()} says why (no elytra, on the ground for Altitude, set back over and over). */
		STUCK
	}

	/** The yaw to fly (degrees), followed at once; until it's given, the way the player faces when it starts. */
	Flight heading(float yaw);

	/** Control: how fast to fly, in blocks a second (0 stays put, and doesn't take off). Altitude's cycle sets its own speed. */
	Flight speed(double blocksPerSecond);

	/**
	 * The height to fly at. Altitude: the cruise height (NaN, the default: where each glide starts), only ever raised, in
	 * time for higher ground ahead. Control: the height held (NaN: where each glide starts), and at least a little above
	 * ground ahead.
	 */
	Flight height(double y);

	/**
	 * Control: climb (or, negative, come down) at this many blocks a second instead of holding {@link #height}, and
	 * without minding the ground ahead; NaN, the default, holds the height again.
	 */
	Flight climb(double blocksPerSecond);

	/** Elytras with less durability than this (%) are put aside for the freshest other one; 0, the default, keeps them on. */
	Flight preserve(double percent);

	/** Called once it's down after landing: with null if {@link #land()} asked for it, else why it had to come down. */
	Flight onLanded(Consumer<@Nullable Why> landed);

	/** Starts flying (the elytra opens the way vanilla's does). Starting it again starts over. */
	void start();

	/** Stops at once, wherever you are: the glide is the player's again. */
	void stop();

	/** Comes down to land where it is; {@link #onLanded} hears null once down. */
	void land();

	State state();

	/** Why it's {@link State#STUCK}, {@link State#LANDING} or {@link State#DOWN}; null otherwise, or when it was asked to land. */
	@Nullable Why why();

	/** What it's doing, in a few words for a HUD line ("Y210", "Steadying", "38 b/s", "Landing"); null when there's nothing to say. */
	@Nullable String status();
}
