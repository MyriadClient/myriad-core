package dev.myriadclient.api.hook;

import dev.myriadclient.api.addon.AddonContext;
import dev.myriadclient.api.util.MyriadId;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * What's hooked ({@code Myriad.hooks()}): the contributions addons made, which of them would handle a call, and which
 * replacement each service is using. Contribute through {@link AddonContext#hook} and
 * {@link AddonContext#replace}.
 */
@ApiStatus.NonExtendable
public interface Hooks {
	/** Every contribution, hooks' and services', in the order they were made. */
	List<Contribution> contributions();

	/** The contributions to the hook or service with this id. */
	List<Contribution> contributions(MyriadId target);

	/** The contributions that would handle a call of {@code hook} for {@code caller} now, first to run first. */
	List<Contribution> chain(Hook<?, ?> hook, @Nullable Object caller);

	/** The replacement {@code service} is using for every caller, or empty for core's own. */
	Optional<Contribution> active(Service<?> service);

	/** Every replacement of {@code service} running here: the one for everyone and those for some callers. */
	List<Contribution> live(Service<?> service);

	/** Core's own implementation of {@code service}, whatever is in use. */
	<S> S core(Service<S> service);

	/** The implementation of {@code service} every caller uses unless one of their own applies: a replacement's, or core's. */
	<S> S current(Service<S> service);

	/** The implementation of {@code service} that serves {@code caller} (a module, usually). */
	<S> S current(Service<S> service, @Nullable Object caller);

	@ApiStatus.Internal
	<I, O> O call(Hook<I, O> hook, @Nullable Object caller, I input, Function<? super I, ? extends O> fallback);

	@ApiStatus.Internal
	boolean hooked(Hook<?, ?> hook);
}
