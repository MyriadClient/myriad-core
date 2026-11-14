package dev.myriadclient.api.hook;

import dev.myriadclient.api.registry.MyriadId;
import dev.myriadclient.api.service.AntiCheat;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * One call of a {@link Hook}, as a {@link HookHandler} sees it: the input, who it's for, where you are, and the way on
 * to the next handler. Don't keep it past the handler. It runs on whatever thread called the hook (the render thread
 * for core's hooks).
 */
@ApiStatus.NonExtendable
public interface HookCall<I, O extends @Nullable Object> {
	/** What the hook was called with. */
	I input();

	/** The module (or other feature) the call is for, by id: null when the caller has no id, or there's no caller. */
	@Nullable MyriadId caller();

	/** The mod id of the addon the caller belongs to (its id's namespace, else by its class), or null if not known. */
	@Nullable String callerAddon();

	/** The object the call is for (a module, usually), or null. */
	@Nullable Object owner();

	/** The anti-cheat profile in effect ({@code Myriad.antiCheat().profile()}, as of this tick). */
	AntiCheat.Profile profile();

	/** The host of the server you're on ("2b2t.org"), lower case without the port; null in singleplayer and the menus. */
	@Nullable String server();

	/** The next handler's answer (a less specific contribution's, or in the end the hook's default). */
	O proceed();

	/** The next handler's answer for a different input (the default's, past the last handler). */
	O proceed(I input);
}
