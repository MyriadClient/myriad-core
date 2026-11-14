package dev.myriadclient.api.registry;

import dev.myriadclient.api.registry.MyriadId;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * An ordered, namespaced registry of one kind of thing (modules, commands, panels, themes...), from {@code Myriad}'s
 * accessors. Every entry remembers which addon registered it, so a failing addon's contributions can be rolled back and
 * the UI can show where things came from. It's read-only here: an addon adds its entries through its
 * {@link dev.myriadclient.api.addon.AddonContext} ({@code registerModules}, {@code registerCommand}, ...), which names it
 * as their owner.
 * <p>
 * Reading ({@link #values()}, a for-each, {@link #size()}) takes no lock and copies nothing: it reads the snapshot taken
 * at the last change, so it's fine every frame.
 *
 * <pre>{@code
 * for (Theme theme : Myriad.themes()) ...;
 * Myriad.commands().get(MyriadId.of("myriad", "friend")).ifPresent(c -> ...);
 * }</pre>
 */
@ApiStatus.NonExtendable
public interface Registry<T extends Identified> extends Iterable<T> {
	/** What the registry holds, for messages ("module", "theme"). */
	String name();

	/** The entry with {@code id}; empty if there's none. */
	Optional<T> get(MyriadId id);

	/** The entry whose class is exactly {@code type} (not a subclass); empty if there's none. */
	<C extends T> Optional<C> get(Class<C> type);

	/** Whether an entry has {@code id}. */
	boolean contains(MyriadId id);

	/** The mod id of the addon that registered {@code entry}; null if it isn't registered. */
	@Nullable String ownerOf(T entry);

	/** Everything the addon with mod id {@code owner} registered, in order; a copy. */
	List<T> ownedBy(String owner);

	/** Every entry in order, as of the last change: unmodifiable, and the same instance until something changes. */
	Collection<T> values();

	/** How many entries there are. */
	int size();

	/**
	 * Calls {@code listener} with each entry registered from now on (on the thread that registers it, the render
	 * thread), not with those already there. A listener can't be removed.
	 */
	void onAdd(Consumer<T> listener);

	/**
	 * Calls {@code listener} with each entry removed from now on (an addon that failed, say). A listener can't be
	 * removed.
	 */
	void onRemove(Consumer<T> listener);
}
