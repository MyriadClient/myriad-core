package dev.myriadclient.api.event.events;

import org.jetbrains.annotations.ApiStatus;
/**
 * The player's movement input for this tick, right after the keyboard was read. Change the flags to press or release
 * keys for this tick only (Scaffold holds sneak at edges, Elytra Bounce holds forward and jump), without touching
 * the user's real key bindings. Movement amounts are recomputed from the flags if you change them.
 */
public final class InputEvent {
	public boolean forward, backward, left, right, jump, sneak, sprint;

	@ApiStatus.Internal
	public InputEvent(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean sneak, boolean sprint) {
		this.forward = forward;
		this.backward = backward;
		this.left = left;
		this.right = right;
		this.jump = jump;
		this.sneak = sneak;
		this.sprint = sprint;
	}

	public boolean isMoving() {
		return forward != backward || left != right;
	}
}
