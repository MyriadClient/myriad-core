package dev.myriadclient.impl.registry;

import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.registry.MyriadId;
import dev.myriadclient.api.registry.Registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The registry behind {@link Registry}: entries are added and removed only here, by core (an addon's go through its
 * AddonContext, which names it as their owner). Changes copy the entry list once so reads stay lock- and copy-free.
 */
public class RegistryImpl<T extends Identified> implements Registry<T> {
	private final String name;
	private final Map<MyriadId, T> entries = new LinkedHashMap<>();
	private final Map<T, String> owners = new IdentityHashMap<>();
	private final List<Consumer<T>> addListeners = new CopyOnWriteArrayList<>();
	private final List<Consumer<T>> removeListeners = new CopyOnWriteArrayList<>();
	/** The entries in order, copied on every change (rare) so reads (every frame) need neither a lock nor a copy. */
	private volatile List<T> snapshot = List.of();

	public RegistryImpl(String name) {
		this.name = name;
	}

	@Override
	public String name() {
		return name;
	}

	/** Registers an entry for {@code owner} (the registering addon's mod id); throws on a duplicate id. */
	public synchronized T register(T entry, String owner) {
		MyriadId id = entry.id();
		if (entries.containsKey(id)) throw new IllegalArgumentException("Duplicate " + name + " id: " + id);
		entries.put(id, entry);
		owners.put(entry, owner);
		snapshot = List.copyOf(entries.values());
		for (Consumer<T> l : addListeners) l.accept(entry);
		return entry;
	}

	public synchronized boolean unregister(MyriadId id) {
		T removed = entries.remove(id);
		if (removed == null) return false;
		owners.remove(removed);
		snapshot = List.copyOf(entries.values());
		for (Consumer<T> l : removeListeners) l.accept(removed);
		return true;
	}

	/** Removes everything {@code owner} registered. Returns the removed entries. */
	public synchronized List<T> unregisterAll(String owner) {
		List<T> removed = new ArrayList<>();
		for (T t : List.copyOf(entries.values())) {
			if (owner.equals(owners.get(t))) {
				unregister(t.id());
				removed.add(t);
			}
		}
		return removed;
	}

	@Override
	public synchronized Optional<T> get(MyriadId id) {
		return Optional.ofNullable(entries.get(id));
	}

	@Override
	public <C extends T> Optional<C> get(Class<C> type) {
		for (T t : snapshot) if (t.getClass() == type) return Optional.of(type.cast(t));
		return Optional.empty();
	}

	@Override
	public synchronized boolean contains(MyriadId id) {
		return entries.containsKey(id);
	}

	@Override
	public synchronized String ownerOf(T entry) {
		return owners.get(entry);
	}

	@Override
	public synchronized List<T> ownedBy(String owner) {
		List<T> list = new ArrayList<>();
		for (T t : entries.values()) if (owner.equals(owners.get(t))) list.add(t);
		return list;
	}

	@Override
	public Collection<T> values() {
		return snapshot;
	}

	@Override
	public int size() {
		return snapshot.size();
	}

	@Override
	public void onAdd(Consumer<T> listener) {
		addListeners.add(listener);
	}

	@Override
	public void onRemove(Consumer<T> listener) {
		removeListeners.add(listener);
	}

	@Override
	public Iterator<T> iterator() {
		return snapshot.iterator();
	}
}
