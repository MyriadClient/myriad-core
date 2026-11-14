package dev.myriadclient.api.hook;

import dev.myriadclient.api.addon.AddonContext;
import dev.myriadclient.api.registry.MyriadId;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * What's hooked ({@code Myriad.hooks()}): the contributions addons made, which of them would handle a call, and which
 * replacement each service is using. Contribute through {@link AddonContext#hook} and
 * {@link AddonContext#replace}. The lists returned are snapshots.
 *
 * <pre>{@code
 * // Which handlers would decide this module's rotation turns right now, first to run first.
 * for (Contribution c : Myriad.hooks().chain(Rotations.TURN, this)) info(c.label() + " " + c.match());
 *
 * // Core's rotations, bypassing any replacement (from inside a replacement, say).
 * Rotations core = Myriad.hooks().core(Service.ROTATIONS);
 * }</pre>
 */
@ApiStatus.NonExtendable
public interface Hooks {
	/** Every contribution, hooks' and services', on or off, in the order they were made. */
	List<Contribution> contributions();

	/** The contributions to the hook or service with this id. */
	List<Contribution> contributions(MyriadId target);

	/**
	 * The contributions that would handle a call of {@code hook} for {@code caller} now (on, not failed, matching),
	 * first to run first; empty when the hook's default would answer.
	 */
	List<Contribution> chain(Hook<?, ?> hook, @Nullable Object caller);

	/** The replacement {@code service} is using for every caller, or empty when that's core's own. */
	Optional<Contribution> active(Service<?> service);

	/** Every replacement of {@code service} running here: the one for everyone and those for some callers. */
	List<Contribution> live(Service<?> service);

	/** Core's own implementation of {@code service}, whatever is in use. */
	<S> S core(Service<S> service);

	/**
	 * The implementation of {@code service} every caller uses unless one scoped to them applies: the replacement for
	 * everyone, or core's.
	 */
	<S> S current(Service<S> service);

	/**
	 * The implementation of {@code service} that serves {@code caller} (a module, usually): a replacement scoped to it,
	 * else {@link #current(Service)}. A null caller gets {@link #current(Service)}.
	 */
	<S> S current(Service<S> service, @Nullable Object caller);

	/** Core's: what {@link Hook#call} goes through. */
	@ApiStatus.Internal
	<I, O extends @Nullable Object> O call(Hook<I, O> hook, @Nullable Object caller, I input, Function<? super I, ? extends O> fallback);

	/** Core's: what {@link Hook#hooked} goes through. */
	@ApiStatus.Internal
	boolean hooked(Hook<?, ?> hook);
}
