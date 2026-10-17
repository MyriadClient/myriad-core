package dev.myriadclient.api.service;

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
public interface TickSpeed {
	/** Sets {@code owner}'s multiplier (1 = normal, 2 = twice as fast); 1 or less than 0.05 clears it. */
	void set(Object owner, float multiplier);

	void clear(Object owner);

	/** The combined multiplier the client is running at. */
	float multiplier();
}
