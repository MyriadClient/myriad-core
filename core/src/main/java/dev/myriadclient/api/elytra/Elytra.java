package dev.myriadclient.api.elytra;

import dev.myriadclient.api.event.events.GlideEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Elytra flight the way strict servers check it, shared by every flight module so that two of them never fight over the
 * glide (each would otherwise hold back the same pings and swap the same chest slot):
 * <ul>
 * <li>{@link NoDurability}: gliding without wearing the elytra, and catching a glide dropped in mid-air;</li>
 * <li>{@link ChestSwap}: swapping the worn elytra with a chestplate in mid-air;</li>
 * <li>{@link GlideHold}: riding out a moment the server stops the glide, unseen by Grim;</li>
 * <li>{@link Pilot}: a flight module flying the local player by its own rotation, and holding the glide open;</li>
 * <li>{@link Flight}: a flight that flies itself along a heading ({@link #altitude}, {@link #control}).</li>
 * </ul>
 * To change the glide's movement itself (fly it at a set speed, boost it), listen to {@link GlideEvent}.
 *
 * <pre>{@code
 * private final NoDurability noDurability = Myriad.elytra().noDurability(this);
 *
 * protected void onEnable() {
 *     Myriad.elytra().addPilot(this);   // this implements Elytra.Pilot
 *     noDurability.enable();
 * }
 * }</pre>
 */
@ApiStatus.NonExtendable
public interface Elytra {
	/**
	 * A flight module flying the local player. While one is added, the glide follows what it says; the camera stays the
	 * player's throughout.
	 */
	interface Pilot {
		/**
		 * Whether the local player moves by the rotation being sent to the server ({@code Rotations.rotationForAction()})
		 * instead of the camera's: the glide's direction and pitch, walking and the jump's push, as the server simulates
		 * them. Unlike {@code Rotations.Options.moveFix} the keys aren't re-pressed: forward goes the sent way. A setback
		 * doesn't turn the camera to the sent rotation meanwhile (some servers set you back to it).
		 */
		default boolean steers() {
			return false;
		}

		/** Whether the local player counts as gliding although the elytra has closed (a bounce touching the ground). */
		default boolean holdsGlide() {
			return false;
		}
	}

	/** Lets {@code pilot} fly the local player until {@link #removePilot}. */
	void addPilot(Pilot pilot);

	void removePilot(Pilot pilot);

	/** A No Durability engine for {@code owner}'s flight: {@link NoDurability#enable() enable} it when the flight starts. */
	NoDurability noDurability(NoDurability.Owner owner);

	ChestSwap chestSwap();

	/**
	 * A flight for {@code owner} that crosses open country without fireworks, for any server: it dives until fast, pulls
	 * up and eases back to level, which holds its height, about 27 blocks a second (about 18 with No Durability where
	 * Grim simulates the glide, so 2b2t's chunks come in ahead of it). It takes off only from the air. The owner says
	 * whether to keep the elytra from wearing and hears its messages.
	 */
	Flight altitude(NoDurability.Owner owner);

	/**
	 * A flight for {@code owner} that flies straight along its heading at a set speed, holding a height, only where the
	 * server doesn't simulate the glide ({@code AntiCheat.Profile#simulatesGlide()} false: NoCheatPlus, singleplayer);
	 * Grim sets it back from the first tick. Speed builds as fast as NoCheatPlus's elytra check allows. It takes off from
	 * the ground by itself while it has a speed.
	 */
	Flight control(NoDurability.Owner owner);

	GlideHold glideHold();
}
