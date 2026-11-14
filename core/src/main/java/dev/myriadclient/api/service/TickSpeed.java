package dev.myriadclient.api.service;

import dev.myriadclient.api.annotation.RenderThread;
import org.jetbrains.annotations.ApiStatus;

/**
 * The speed the client runs game ticks at ("timer"). Several features can speed it up or slow it down at once without
 * overwriting each other: each owner sets its own multiplier and the client runs at their product.
 *
 * <pre>{@code
 * Myriad.tickSpeed().set(this, 1.08f); // in onEnable, or while a trick needs it
 * Myriad.tickSpeed().clear(this);      // a module's multiplier is also cleared when it's disabled
 * }</pre>
 */
@ApiStatus.NonExtendable
@RenderThread
public interface TickSpeed {
	/**
	 * Sets {@code owner}'s multiplier (1 = normal, 2 = twice as fast, 0.5 = half); 1, less than 0.05 or NaN clears it.
	 */
	void set(Object owner, float multiplier);

	/** Clears {@code owner}'s multiplier; done for a module when it's turned off. */
	void clear(Object owner);

	/** The combined multiplier the client is running at: every owner's multiplied together, 1 when none is set. */
	float multiplier();
}
