package dev.myriadclient.api.hook;

import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.registry.MyriadId;
import org.jetbrains.annotations.ApiStatus;

/**
 * A hook handler or service replacement an addon contributed. The player can turn it off in the Hooks window; one
 * that throws is turned off for the session (and the call goes on to the next). Its {@link #id()} is made from the
 * addon's namespace, the target and the label, so the player's choice sticks across restarts.
 */
@ApiStatus.NonExtendable
public interface Contribution extends Identified {
	/** What it's for: a {@link Hook}'s or a {@link Service}'s id. */
	MyriadId target();

	/** What it does, as the addon put it ("Smooth turns"). */
	String label();

	/** When it applies: for which callers, on which anti-cheat profile and server. */
	Match match();

	/** Whether the player left it on (saved). */
	boolean enabled();

	/**
	 * Turns it on or off, as the Hooks window does; saved at once. Hook chains and the replacement each service uses are
	 * worked out again, so a replacement is started or stopped right away.
	 */
	void setEnabled(boolean enabled);

	/** Whether it threw and was turned off until the game restarts. */
	boolean failed();
}
