package dev.myriadclient.api.hook;

import dev.myriadclient.api.addon.AddonContext;
import org.jetbrains.annotations.ApiStatus;

/**
 * Handles a hook ({@link AddonContext#hook}): name it, say when it applies, then give the
 * handler, which registers it.
 *
 * <pre>{@code
 * ctx.hook(Rotations.TURN)
 *     .named("Eased turns")
 *     .when(Match.addon(ctx.namespace()).on(AntiCheat.Profile.GRIM))
 *     .handle(call -> call.input().speed() > 0 ? ease(call.input()) : call.proceed());
 * }</pre>
 */
@ApiStatus.NonExtendable
public interface HookBuilder<I, O> {
	/** What the player sees in the Hooks window (default: the hook's name). Unique among your addon's handlers of it. */
	HookBuilder<I, O> named(String label);

	/** When it applies (default: {@link Match#always()}). */
	HookBuilder<I, O> when(Match match);

	/** Registers {@code handler}. */
	Contribution handle(HookHandler<I, O> handler);
}
