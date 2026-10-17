package dev.myriadclient.api.module;

import dev.myriadclient.api.Myriad;
import org.jetbrains.annotations.Nullable;

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
}
