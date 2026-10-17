package dev.myriadclient.api.elytra;

import org.jetbrains.annotations.ApiStatus;

/**
 * Swapping the worn elytra with a chestplate held in the off hand or hotbar, in mid-air: how {@link NoDurability} and a
 * bounce keep the server from gliding long enough to wear the elytra. The chestplate goes on by using it (as a
 * right-click equips armour); the elytra goes back on with one inventory click whenever the server takes one, otherwise
 * by using it too. The hotbar, hands, armour and model keep showing what they did meanwhile, and the server's equip
 * sound for each swap is muted.
 */
@ApiStatus.NonExtendable
public interface ChestSwap {
	/** What {@link #ensurePair} found. */
	enum Need {
		/** The counterpart is at hand: swaps can go. */
		READY,
		/** It's being brought into the hotbar, or what's worn can't be trusted yet (a swap is on its way): ask again. */
		WAITING,
		/** There's no counterpart anywhere (for a while: not just a swap's moment): say so. */
		MISSING,
		/** There is one, in the inventory, but no hotbar slot to bring it into. */
		NO_ROOM
	}

	/** Whether a swap can go now: something to swap with, and no Curse of Binding on either. */
	boolean ready();

	/**
	 * Whether a glide could start: an elytra is worn, or ready to be swapped on, that isn't one hit from breaking (the
	 * server refuses a glide start with that one).
	 */
	boolean canGlide();

	/** Whether an elytra is worn. */
	boolean elytraWorn();

	/**
	 * Swaps what's worn with its counterpart at hand: a chestplate for the elytra, the elytra for a chestplate. False if
	 * there's nothing to swap with.
	 */
	boolean swap();

	/**
	 * Starts the glide again on the server: the elytra goes on if it isn't, and the start is sent. The caller sends the
	 * answers {@link GlideHold} held first, presses jump this tick (released the tick before, as Grim expects) and swaps
	 * the chestplate back on after, if it wants to.
	 */
	boolean startGlide();

	/**
	 * Whether the server has answered the last swap, so what's worn can be trusted again. Until then the client may show
	 * a moment that's already past.
	 */
	boolean settled();

	/** Puts the elytra back on if a chestplate is worn and the elytra is at hand. */
	void restoreElytra();

	/**
	 * Makes sure the counterpart of what's worn is at hand, bringing it into the hotbar from the inventory if it isn't
	 * (at most one click every half second, never while a swap is on its way). The server refuses a click while you move
	 * or sprint: with {@code mayStop} your keys are let go for a tick first, otherwise it waits for a moment you're still.
	 */
	Need ensurePair(boolean mayStop);
}
