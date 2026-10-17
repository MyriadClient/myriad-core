package dev.myriadclient.api.elytra;

import org.jetbrains.annotations.ApiStatus;

/**
 * No Durability for an elytra, and a catch of a glide dropped in mid-air: one engine per flight
 * ({@link Elytra#noDurability}), enabled while it flies. Only one runs its swapping at a time.
 * <p>
 * The server wears an elytra by one for every 20 ticks of gliding without a break. Where Grim simulates the glide
 * (2b2t), the elytra is swapped for a chestplate in the off hand or hotbar while you glide, so the server stops the
 * glide on its next tick; the client keeps gliding through that ({@link GlideHold}), and every 8 ticks the elytra goes
 * back on just long enough to start the glide again (jump pressed, as vanilla starts one). The server never glides you
 * for more than a tick in one go. Rockets used meanwhile go out in those moments, when the server takes them; something
 * to eat waits for the swapping to stop. Swaps only run with room around you, and not into chunks Grim doesn't count as
 * yours yet. Elsewhere (no anti-cheat, or one that doesn't simulate the glide, like NoCheatPlus) the glide is broken with
 * two glide starts instead, holding still for a tick, and no chestplate is needed.
 * <p>
 * The catch, on either way: the glide stopping in mid-air with nothing a player would stop it for opens the elytra
 * again at once, unless you'd land without hurting.
 */
@ApiStatus.NonExtendable
public interface NoDurability {
	/** Who runs it: whether swapping is on, and where its messages go. */
	interface Owner {
		/** Whether to keep the elytra from wearing now (the catch runs either way). */
		boolean noDurability();

		/**
		 * For long fast glides (an Altitude-style cycle): each start of the glide waits for the server to have the elytra
		 * back on. With both in one tick, 2b2t ignored a start now and then on those glides.
		 */
		default boolean equipBeforeStart() {
			return false;
		}

		/** Whether the owner lets the glide drop and opens it again itself (a bounce): the catch keeps out of it. */
		default boolean letsGlideDrop() {
			return false;
		}

		void info(String message);

		void warn(String message);
	}

	/** Starts watching the glide; listens for input, item use and setbacks on its own from now. */
	void enable();

	/** Stops, with the elytra back on and the glide started again if the server had stopped it. */
	void disable();

	/**
	 * Call once a tick while enabled, in {@code TickEvent.Pre} before the tick's actions
	 * ({@code Priority.BEFORE_ACTIONS + 10}) and after your own flight: a restart sends held ping answers, which have to
	 * go first.
	 */
	void tick();

	/** Whether the elytra and chestplate are being swapped (or the elytra put back for good) right now. */
	boolean swapping();

	/** Whether swaps had to wait for chunks Grim doesn't count as yours lately: a fast flight should slow down. */
	boolean chunksLate();

	/**
	 * Whether the chestplate (or, while it's worn, the elytra) is at hand to swap with, bringing it into the hotbar if
	 * not (see {@link ChestSwap#ensurePair}); warns the owner once when it can't be.
	 */
	boolean chestplateAtHand(boolean mayStop);
}
