package dev.myriadclient.api.service;

import org.jetbrains.annotations.ApiStatus;
/**
 * A shared budget for packets servers count, so that several modules acting at once (or one module on a fast loop)
 * don't add up to a kick. Every packet of each kind is counted as it's sent, by vanilla or by any feature, and
 * Myriad's services ({@code placement()}, {@code inventory()}) check the budget before they send, returning false
 * instead of sending when it's spent. It refills continuously, so try again next tick.
 *
 * <p>Check it yourself before sending your own packets of these kinds:
 *
 * <pre>{@code
 * if (!Myriad.limits().canSend(PacketLimits.Kind.INTERACT, 1)) return; // next tick
 * }</pre>
 *
 * Wrap actions that must happen whatever the cost, like putting a totem in your off hand, in {@link #urgent}.
 */
@ApiStatus.NonExtendable
public interface PacketLimits {
	enum Kind {
		/** Block digging actions: starting, stopping and aborting a break, swapping hands, dropping the held item. */
		BLOCK_ACTION,
		/** Using items, using them on blocks (placing) and on entities, and attacking. */
		INTERACT,
		/** Clicks in inventories and containers. */
		INVENTORY
	}

	/** Whether {@code packets} more of {@code kind} fit in the budget right now (always true inside {@link #urgent}). */
	boolean canSend(Kind kind, int packets);

	/** Packets of {@code kind} sent in the current window. */
	int used(Kind kind);

	/** How many packets of {@code kind} the budget allows per window ({@link #windowMs(Kind)}). */
	int limit(Kind kind);

	/** The length of the window {@code kind}'s budget counts over, in milliseconds. */
	int windowMs(Kind kind);

	/**
	 * The window block actions and inventory clicks count over, in milliseconds.
	 *
	 * @deprecated the windows differ by kind since 0.5.0 (interactions follow Paper's spam limiter); use {@link #windowMs(Kind)}.
	 */
	@Deprecated
	int windowMs();

	/** Runs {@code action} with the budget ignored, for actions that matter more than the risk. Packets still count. */
	void urgent(Runnable action);
}
