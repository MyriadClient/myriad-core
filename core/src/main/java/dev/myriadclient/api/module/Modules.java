package dev.myriadclient.api.module;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.util.AnyThread;
import dev.myriadclient.api.util.MyriadId;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Typed access to registered modules, safe to call from anywhere: mixins, other addons, before Myriad has started,
 * or when the module's addon isn't installed (you get null rather than an exception). Lookups are a single map read,
 * so they're fine in per-frame and per-entity hooks.
 *
 * <p>This is the standard way for a mixin to reach its module; there's no need for a static {@code INSTANCE}. What
 * the mixin then does for the module goes through {@link #guard}, so a bug in it turns the module off instead of
 * crashing the game:
 *
 * <pre>{@code
 * @Inject(method = "renderWeather", at = @At("HEAD"), cancellable = true)
 * private void my$noWeather(CallbackInfo ci) {
 *     NoWeather m = Modules.active(NoWeather.class);
 *     if (m != null && Modules.guard(m, () -> m.rain.get(), false)) ci.cancel();
 * }
 * }</pre>
 */
@AnyThread
public final class Modules {
	/** Failures a module's guarded code may have before the module is turned off: as many as an event handler may. */
	private static final int GUARD_FAILURES = 50;
	/** The first few failures are logged with their stack trace; the rest are only counted. */
	private static final int GUARD_LOGGED = 3;
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Module");
	private static volatile @Nullable BiConsumer<Module, Throwable> guardHook;

	private Modules() {
	}

	/** The registered instance of {@code type}, or null if it isn't registered (yet). */
	public static <M extends Module> @Nullable M get(Class<M> type) {
		return Myriad.isReady() ? Myriad.modules().getOrNull(type) : null;
	}

	/** The module if it is registered and enabled, otherwise null. */
	public static <M extends Module> @Nullable M active(Class<M> type) {
		M m = get(type);
		return m != null && m.isEnabled() ? m : null;
	}

	public static boolean isActive(Class<? extends Module> type) {
		return active(type) != null;
	}

	/**
	 * Holds {@code module} off for {@code owner} (a module, a job): it turns off now if it's on, stays off while any
	 * owner holds it, and comes back on when the last one {@linkplain #resume lets go}, if the player has it on then.
	 * Meanwhile the menu shows who holds it, the player turning it on only takes effect afterwards, and the profile
	 * saves it as the player has it, never as off because of this. For work that would fight another module over the
	 * player (a search flight and Elytra Fly, say). A module that turns off lets go of what it held. Render thread only.
	 *
	 * <pre>{@code
	 * Modules.suspend(this, MyriadId.of("myriad-essentials", "elytra_fly"));
	 * ...
	 * Modules.resume(this);
	 * }</pre>
	 */
	public static void suspend(Object owner, Module module) {
		Objects.requireNonNull(owner);
		module.suspend(owner);
	}

	/** {@link #suspend(Object, Module)} by type; nothing if it isn't registered. */
	public static void suspend(Object owner, Class<? extends Module> type) {
		Module m = get(type);
		if (m != null) suspend(owner, m);
	}

	/** {@link #suspend(Object, Module)} by id, for another addon's module; nothing if it isn't installed. */
	public static void suspend(Object owner, MyriadId id) {
		if (Myriad.isReady()) Myriad.modules().get(id).ifPresent(m -> suspend(owner, m));
	}

	/**
	 * Runs {@code body}, code a mixin runs for {@code module}, so that an exception in it can't crash the game. The bus
	 * does this for event handlers, but a mixin calls into its module directly, from the middle of vanilla's code. A
	 * failure is logged (the first few with their stack trace) and counted; at {@value #GUARD_FAILURES} the module is
	 * turned off with a notice saying so, and the count starts over when it's turned on again. Nothing runs for a null
	 * module. Any thread; the module is turned off on the render thread.
	 *
	 * <pre>{@code
	 * @Inject(method = "createTitle", at = @At("RETURN"), cancellable = true)
	 * private void my$windowTitle(CallbackInfoReturnable<String> cir) {
	 *     WindowTitle m = Modules.active(WindowTitle.class);
	 *     if (m != null) Modules.guard(m, () -> cir.setReturnValue(m.title.get()));
	 * }
	 * }</pre>
	 *
	 * Check the lookup before the guard, as there: the lambda is made at the call, so while the module is off nothing
	 * is allocated.
	 */
	public static void guard(@Nullable Module module, Runnable body) {
		if (module == null) return;
		try {
			body.run();
		} catch (Throwable t) {
			failed(module, t);
		}
	}

	/** {@link #guard(Module, Runnable)} for code that answers: {@code fallback} if it throws, or for a null module. */
	public static <T> T guard(@Nullable Module module, Supplier<T> body, T fallback) {
		if (module == null) return fallback;
		try {
			return body.get();
		} catch (Throwable t) {
			failed(module, t);
			return fallback;
		}
	}

	private static void failed(Module module, Throwable t) {
		// Out of memory and the like aren't the module's: the game can't go on as if nothing happened.
		if (t instanceof VirtualMachineError e && !(e instanceof StackOverflowError)) throw e;
		var hook = guardHook;
		if (hook != null) hook.accept(module, t);
		int n = module.guardFailures.incrementAndGet();
		if (n <= GUARD_LOGGED) LOG.error("{} threw in code a mixin runs for it", module.id(), t);
		if (n != GUARD_FAILURES) return;
		LOG.error("Turned {} off after {} failures in code its mixins run", module.id(), n);
		Minecraft.getInstance().execute(() -> {
			module.setEnabled(false);
			Myriad.notifications().error(module.name(), "Turned off: it kept failing. The log says where.");
		});
	}

	/** Told of every guarded failure (dev tooling: the self-test attributes them to the module under test). */
	@ApiStatus.Internal
	public static void setGuardHook(@Nullable BiConsumer<Module, Throwable> hook) {
		guardHook = hook;
	}

	/** Lets go of every module {@code owner} holds off; each comes back on with its last suspender, if the player has it on. */
	public static void resume(Object owner) {
		if (!Myriad.isReady()) return;
		for (Module m : Myriad.modules().values()) m.release(owner);
	}
}
