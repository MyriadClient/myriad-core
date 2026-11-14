package dev.myriadclient.api.hook;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.AddonContext;
import dev.myriadclient.api.registry.MyriadId;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * One decision that addons can make instead of the code that owns it: the owner calls the hook with what it knows and
 * its own answer as the default, and contributions ({@link AddonContext#hook}) that match
 * the call give theirs. With none, the default runs directly, so an unhooked hook costs a map lookup.
 * <p>
 * Declare a hook once, as a constant, where the addons that will handle it can compile against it (your API classes),
 * with an immutable input that says everything a handler needs:
 *
 * <pre>{@code
 * public record SwapMoment(boolean gliding, int ticksSinceSwap) {}
 * public static final Hook<SwapMoment, Boolean> MAY_SWAP = Hook.of("my-addon:may_swap", "May swap",
 *     "Whether the chest slot may be swapped now.");
 *
 * boolean may = MAY_SWAP.call(this, new SwapMoment(gliding, ticks), m -> m.ticksSinceSwap() > 4);
 * }</pre>
 * Keep hooks for decisions other addons have a reason to make differently (per module, per server), and call them
 * where the answer is needed rather than every frame where you can avoid it.
 * <p>
 * Calls may come from any thread the owner calls from; core's own hooks are called on the render thread.
 *
 * @param <I> what a call says (immutable; a record)
 * @param <O> the answer (nullable where the hook says null means something, as {@code Placement.AIM} does)
 */
public final class Hook<I, O extends @Nullable Object> {
	private static final List<Hook<?, ?>> ALL = new CopyOnWriteArrayList<>();

	private final MyriadId id;
	private final String name;
	private final String description;

	private Hook(MyriadId id, String name, @Nullable String description) {
		this.id = Objects.requireNonNull(id);
		this.name = Objects.requireNonNull(name);
		this.description = description == null ? "" : description;
	}

	/**
	 * Declares a hook. Make it once, as a {@code static final} constant: each call adds to {@link #all()}, and
	 * contributions find it by identity.
	 *
	 * @param id          unique, in your addon's namespace ({@code my-addon:may_swap})
	 * @param name        what the Hooks window shows ("May swap")
	 * @param description one sentence for the Hooks window; null for none
	 */
	public static <I, O extends @Nullable Object> Hook<I, O> of(MyriadId id, String name, @Nullable String description) {
		Hook<I, O> hook = new Hook<>(id, name, description);
		ALL.add(hook);
		return hook;
	}

	/** {@link #of(MyriadId, String, String)} with the id as text ({@code "my-addon:may_swap"}). */
	public static <I, O extends @Nullable Object> Hook<I, O> of(String id, String name, @Nullable String description) {
		return of(MyriadId.parse(id), name, description);
	}

	/** Every hook declared so far (a class declares its hooks when it loads). */
	public static List<Hook<?, ?>> all() {
		return List.copyOf(ALL);
	}

	/** The id it was declared with. */
	public MyriadId id() {
		return id;
	}

	/** The name the Hooks window shows; also the default label of a contribution to it. */
	public String name() {
		return name;
	}

	/** What it decides, for the Hooks window; empty if none was given. */
	public String description() {
		return description;
	}

	/**
	 * The answer for {@code input}: the matching contributions', most specific first, else {@code fallback}'s. Before
	 * Myriad has started, or with no contribution on, {@code fallback} answers directly.
	 *
	 * @param caller   who the call is for (your module, usually: contributions can match it); may be null
	 * @param fallback the answer without contributions, and what the last of them gets from {@link HookCall#proceed()}
	 */
	public O call(@Nullable Object caller, I input, Function<? super I, ? extends O> fallback) {
		if (!Myriad.isReady()) return fallback.apply(input);
		return Myriad.hooks().call(this, caller, input, fallback);
	}

	/** Whether any contribution is on for this hook: skip building an expensive input when not. */
	public boolean hooked() {
		return Myriad.isReady() && Myriad.hooks().hooked(this);
	}

	@Override
	public String toString() {
		return "Hook[" + id + "]";
	}
}
