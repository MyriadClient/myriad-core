package dev.myriadclient.api.module;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.util.MyriadId;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Typed access to registered modules, safe to call from anywhere: mixins, other addons, before Myriad has started,
 * or when the module's addon isn't installed (you get null rather than an exception). Lookups are a single map read,
 * so they're fine in per-frame and per-entity hooks.
 *
 * <p>This is the standard way for a mixin to reach its module; there's no need for a static {@code INSTANCE}:
 *
 * <pre>{@code
 * @Inject(method = "renderWeather", at = @At("HEAD"), cancellable = true)
 * private void my$noWeather(CallbackInfo ci) {
 *     NoWeather m = Modules.active(NoWeather.class);
 *     if (m != null && m.rain.get()) ci.cancel();
 * }
 * }</pre>
 */
public final class Modules {
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

	/** Lets go of every module {@code owner} holds off; each comes back on with its last suspender, if the player has it on. */
	public static void resume(Object owner) {
		if (!Myriad.isReady()) return;
		for (Module m : Myriad.modules().values()) m.release(owner);
	}
}
