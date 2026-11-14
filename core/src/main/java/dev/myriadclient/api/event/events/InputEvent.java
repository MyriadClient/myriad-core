package dev.myriadclient.api.event.events;

import org.jetbrains.annotations.ApiStatus;

/**
 * The player's movement input for this tick, right after the keyboard was read. Change the flags to press or release
 * keys for this tick only (Scaffold holds sneak at edges, Elytra Bounce holds forward and jump), without touching
 * the user's real key bindings. Movement amounts are recomputed from the flags if you change them.
 * <p>
 * Posted on the render thread during the player's tick (between {@code TickEvent.Pre} and {@code TickEvent.Post}),
 * only while something listens. Not cancellable.
 */
public final class InputEvent {
	/**
	 * Whether each movement key counts as held this tick: walk forward, back, strafe left, right, jump, sneak and
	 * sprint. Start as the real keys (or Baritone's input) left them.
	 */
	public boolean forward, backward, left, right, jump, sneak, sprint;

	/** Core's; only Myriad makes these. */
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

	/** Whether the keys walk somewhere: forward or back, left or right, without the opposite key cancelling it. */
	public boolean isMoving() {
		return forward != backward || left != right;
	}
}
