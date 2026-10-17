package dev.myriadclient.api.hook;

/**
 * Optional for a service replacement ({@link Service}): told when it starts and stops being the one in use. While in
 * use it's also subscribed to Myriad's events, like an enabled module, so {@code @Subscribe} methods on it run only
 * then. The services' {@code Forwarding} classes implement this already.
 */
public interface Replacement {
	/** It's the service in use from now (the server or the player's choice changed, or the game started). */
	default void activated() {
	}

	/** It's no longer in use: let go of anything you hold (rotations, held slots, queued actions). */
	default void deactivated() {
	}
}
