package dev.myriadclient.api.hook;

import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.util.MyriadId;
import org.jetbrains.annotations.ApiStatus;

/**
 * A hook handler or service replacement an addon contributed. The player can turn it off in the Hooks window; one
 * that throws is turned off for the session (and the call goes on to the next).
 */
@ApiStatus.NonExtendable
public interface Contribution extends Identified {
	/** What it's for: a {@link Hook}'s or a {@link Service}'s id. */
	MyriadId target();

	/** What it does, as the addon put it ("Smooth turns"). */
	String label();

	Match match();

	/** Whether the player left it on (saved). */
	boolean enabled();

	void setEnabled(boolean enabled);

	/** Whether it threw and was turned off until the game restarts. */
	boolean failed();
}
