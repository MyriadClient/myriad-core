package dev.myriadclient.api.hook;

import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.util.MyriadId;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** One call of a {@link Hook}, as a {@link HookHandler} sees it. Don't keep it past the handler. */
@ApiStatus.NonExtendable
public interface HookCall<I, O> {
	/** What the hook was called with. */
	I input();

	/** The module (or other feature) the call is for, by id, if it has one. */
	@Nullable MyriadId caller();

	/** The mod id of the addon the caller belongs to, if known. */
	@Nullable String callerAddon();

	/** The object the call is for (a module, usually), or null. */
	@Nullable Object owner();

	/** The anti-cheat profile in effect. */
	AntiCheat.Profile profile();

	/** The host of the server you're on ("2b2t.org"), or null in singleplayer. */
	@Nullable String server();

	/** The next handler's answer (a less specific contribution's, or in the end the hook's default). */
	O proceed();

	/** The next handler's answer for a different input. */
	O proceed(I input);
}
