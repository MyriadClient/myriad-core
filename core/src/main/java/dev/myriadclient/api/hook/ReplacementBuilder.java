package dev.myriadclient.api.hook;

import dev.myriadclient.api.addon.AddonContext;
import org.jetbrains.annotations.ApiStatus;

import java.util.function.Function;

/**
 * Takes over a core service ({@link AddonContext#replace}): name it, say when and for whom
 * it applies, then give what makes it, which registers it.
 *
 * <pre>{@code
 * ctx.replace(Service.ROTATIONS)
 *     .named("Humanised rotations")
 *     .when(Match.addon(ctx.namespace()))     // only this addon's modules; leave out for everyone
 *     .with(MyRotations::new);                 // gets core's, returns yours
 * }</pre>
 */
@ApiStatus.NonExtendable
public interface ReplacementBuilder<S> {
	/** What the player sees in the Hooks window (default: the service's name). Unique among your addon's replacements of it. */
	ReplacementBuilder<S> named(String label);

	/** When and for whom it applies (default: {@link Match#always()}, everyone everywhere). */
	ReplacementBuilder<S> when(Match match);

	/**
	 * Registers it: {@code factory} gets core's implementation and returns yours (usually a subclass of the service's
	 * {@code Forwarding} class), made the first time it's needed and kept after.
	 */
	Contribution with(Function<? super S, ? extends S> factory);
}
