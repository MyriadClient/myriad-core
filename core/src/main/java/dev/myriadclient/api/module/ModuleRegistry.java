package dev.myriadclient.api.module;

import dev.myriadclient.api.registry.Registry;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Every registered module, from {@link dev.myriadclient.api.Myriad#modules()}: a {@link Registry} with lookups by class,
 * name, tag and window. For quick typed access from your own code and mixins, see {@link Modules}.
 *
 * <pre>{@code
 * KillAura aura = Myriad.modules().getOrNull(KillAura.class);
 * Myriad.modules().byName("kill aura").ifPresent(Module::toggle);
 * }</pre>
 */
@ApiStatus.NonExtendable
public interface ModuleRegistry extends Registry<Module> {
	/** {@link #get(Class)} without the Optional: the module of exactly that class, or null when it isn't registered. */
	<C extends Module> @Nullable C getOrNull(Class<C> type);

	/**
	 * Finds a module by display name ("Kill Aura", "killaura"), id path ("kill_aura") or full id, ignoring case and spaces;
	 * the first registered wins when two addons have modules of the same name. Empty when none matches.
	 */
	Optional<Module> byName(String name);

	/** Every module with {@code tag} (as its main tag or another), in registration order; a copy. */
	List<Module> withTag(Tag tag);

	/** The modules of {@code group}, in registration order (their order in its window); a copy. */
	List<Module> inGroup(ModuleGroup group);

	/**
	 * Every group with modules, each one window in the menu: addons in the order their modules were registered, each
	 * addon's groups in the order it declared them, then its own window. Unmodifiable, and kept until a module or group
	 * is added or removed (the menu asks every frame).
	 */
	List<ModuleGroup> groups();

	/** The modules that are on, in registration order; a copy. */
	List<Module> enabled();

	/** Whether the module of exactly {@code type} is registered and on. */
	boolean isEnabled(Class<? extends Module> type);
}
